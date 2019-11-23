package eu.bruscino.dev.sshs.server;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.Gson;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.regression.StreamingLinearRegressionWithSGD;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class SparkApp {

   private static final Logger logger;

   private static final Duration BATCH_DURATION = new Duration(1000);

   private static final Duration FORECAST_DURATION = new Duration(3 * 60 * 60 * 1000);
   private static final Duration TRAINING_PERIOD = new Duration(30 * 60 * 1000);

   private static final String helpOption = "help";
   private static final String brokerUrlOption = "broker-url";
   private static final String clientPrefixOption = "client-prefix";
   private static final String usernameOption = "username";
   private static final String passwordOption = "password";
   private static final String topicOption = "topic";
   private static final String modelTrainingOption = "model-training\"";
   private static final String weatherUrlOption = "weather-url";

   private static String brokerUrl = "ssl://127.0.0.1:8883";
   private static String clientPrefix = "sparkClient";
   private static String username = "brusdev";
   private static String password = "password";
   private static String topic = "sshs/gateway";
   private static String weatherUrl = "http://127.0.0.1:1880/weather";
   private static boolean modelTraining = false;

   static {
      logger = Logger.getLogger(SparkApp.class);
      logger.addAppender(new ConsoleAppender(new PatternLayout(
         "%d{yyyy-MM-dd HH:mm:ss} %p %c{1}:%L - %m%n")));
   }

   public static void main(String[] args) throws Exception {
      logger.info(String.format("Loading %s ...", SparkApp.class.getName()));

      loadEnvironmentVariables();

      parseCommandLine(args);

      logger.info(String.format("%s: %s", brokerUrlOption, brokerUrl));
      logger.info(String.format("%s: %s", clientPrefixOption, clientPrefix));
      logger.info(String.format("%s: %s", usernameOption, username));
      logger.info(String.format("%s: %s", passwordOption, password));
      logger.info(String.format("%s: %s", topicOption, topic));
      logger.info(String.format("%s: %s", modelTrainingOption, modelTraining));
      logger.info(String.format("%s: %s", weatherUrlOption, weatherUrl));


      SparkConf conf = new SparkConf().setAppName(SparkApp.class.getName());

      if (!conf.contains("spark.master")) {
         conf.set("spark.master", "local[*]");
      }

      JavaStreamingContext ssc = createStreamingContext(conf);

      ssc.start();
      ssc.awaitTermination();
   }

   private static JavaStreamingContext createStreamingContext(SparkConf conf) {
      JavaStreamingContext streamingContext = new JavaStreamingContext(conf, BATCH_DURATION);

      StreamingLinearRegressionWithSGD model = new StreamingLinearRegressionWithSGD().
         setInitialWeights(Vectors.dense(new double[]{1, 1, 0.1, 0.2}));

      JavaDStream<InputData> inputDataStream = createInputDataStream(streamingContext);

      if (modelTraining) {
         trainModel(model, inputDataStream);
      }

      JavaDStream<Double> tankForecastStream = createTankForecastStream(model, inputDataStream);

      sendForecastStream(streamingContext, tankForecastStream);

      return streamingContext;
   }

   private static JavaDStream<InputData> createInputDataStream(JavaStreamingContext streamingContext) {
      final Broadcast<String> weatherUrlBroadcast = streamingContext.sparkContext().broadcast(weatherUrl);

      JavaDStream<String> tankTemperatureStream = streamingContext.receiverStream(
         new MqttReceiver(StorageLevel.MEMORY_ONLY(), brokerUrl, clientPrefixOption + "Receiver",
                          username, password, topic + "/tank/temperature"));

      JavaDStream<InputData> inputDataStream = tankTemperatureStream.map((Function<String, InputData>) tankTemperatureString -> {
         WeatherForecast weatherForecast = WeatherForecast.DEFAULT;

         try {
            Gson weatherGson = new Gson();
            HttpURLConnection weatherConnection = (HttpURLConnection) new URL(weatherUrlBroadcast.getValue()).openConnection();
            weatherForecast = weatherGson.fromJson(new InputStreamReader(weatherConnection.getInputStream()), WeatherForecast.class);
         } catch (Exception e) {
            logger.error("Exception getting weather " + e);
         }

         return new InputData(Double.parseDouble(tankTemperatureString), weatherForecast.getTemperature(), weatherForecast.getHumidity(), weatherForecast.getWind());
      });

      return inputDataStream;
   }

   private static void trainModel(StreamingLinearRegressionWithSGD model, JavaDStream<InputData> inputDataStream) {
      JavaDStream<LabeledPoint> trainingModelDataStream = inputDataStream.map(inputData -> {
         return new TrainingData(inputData.getTankTemperature(), inputData.getWeatherTemperature(),
                                 inputData.getWeatherHumidity(), inputData.getWeatherWind(), Double.NaN);
      }).reduceByWindow((trainingData, trainingDataIn) -> {
         return trainingDataIn;
      }, (Function2<TrainingData, TrainingData, TrainingData>) (trainingData, trainingDataOut) -> {
         return new TrainingData(trainingData.getTankTemperature(), trainingData.getWeatherTemperature(),
                                 trainingData.getWeatherHumidity(), trainingData.getWeatherWind(),
                                 trainingDataOut.getTankForecast());
      }, FORECAST_DURATION, TRAINING_PERIOD).filter(trainingData -> {
         return trainingData.getTankForecast() != Double.NaN;
      }).map(trainingData -> {
         return new LabeledPoint(trainingData.getTankForecast(), Vectors.dense(new double[] {
            trainingData.getTankTemperature(), trainingData.getWeatherTemperature(),
            trainingData.getWeatherHumidity(), trainingData.getWeatherWind() }));
      });

      model.trainOn(trainingModelDataStream);
   }

   private static JavaDStream<Double> createTankForecastStream(StreamingLinearRegressionWithSGD model, JavaDStream<InputData> inputDataStream) {
      JavaDStream<Vector> inputModelDataStream = inputDataStream.reduceByWindow((inputData, inputDataIn) -> {
         return inputDataIn;
      }, new Duration(5 * 1000), new Duration(5 * 1000)).map(inputData -> {
         return Vectors.dense(new double[] { inputData.getTankTemperature(), inputData.getWeatherTemperature(),
            inputData.getWeatherHumidity(), inputData.getWeatherWind() });
      });

      return model.predictOn(inputModelDataStream);
   }

   private static void sendForecastStream(JavaStreamingContext streamingContext, JavaDStream<Double> forecastStream) {
      final Broadcast<String> brokerUrlBroadcast = streamingContext.sparkContext().broadcast(brokerUrl);
      final Broadcast<String> clientPrefixBroadcast = streamingContext.sparkContext().broadcast(clientPrefix);
      final Broadcast<String> usernameBroadcast = streamingContext.sparkContext().broadcast(username);
      final Broadcast<String> passwordBroadcast = streamingContext.sparkContext().broadcast(password);
      final Broadcast<String> topicBroadcast = streamingContext.sparkContext().broadcast(topic);

      forecastStream.foreachRDD(rdd -> {
         rdd.foreachPartition(partitionIterator -> {
            if (partitionIterator.hasNext()) {
               String tankForecastTopic = topicBroadcast.getValue() + "/tank/forecast";
               MqttBuilder mqttBuilder = new MqttBuilder().setBrokerUrl(brokerUrlBroadcast.getValue()).
                  setClientId(clientPrefixBroadcast.getValue() + "Sender").setPersistence(new MemoryPersistence()).
                  setUsername(usernameBroadcast.getValue()).setPassword(passwordBroadcast.getValue()).
                  setCheckSSLPeers(false).setCheckSSLHostname(false);

               MqttClient mqttClient = mqttBuilder.createClient();
               MqttConnectOptions mqttConnectOptions = mqttBuilder.createConnectOptions();

               try {
                  logger.info(String.format("Connecting to %s ...", brokerUrlBroadcast.value()));
                  mqttClient.connect(mqttConnectOptions);

                  while (partitionIterator.hasNext()) {
                     Double forecast = partitionIterator.next();

                     logger.info(String.format("Sending message %s to %s ...", forecast, tankForecastTopic));
                     mqttClient.publish(tankForecastTopic, forecast.toString().getBytes(), 1, false);
                  }

                  logger.info(String.format("Disconnecting from %s ...", brokerUrlBroadcast.value()));
                  mqttClient.disconnect();

                  mqttClient.close();
               } catch (Exception e) {
                  logger.error("Error on connection for sending", e);
               }
            } else {
               logger.info("The partition is empty");
            }
         });
      });
   }

   private static void loadEnvironmentVariables() {
      String optionValue;

      optionValue = System.getenv("SSHS_BROKER_URL");
      if (optionValue != null) {
         brokerUrl = optionValue;
      }
      optionValue = System.getenv("SSHS_CLIENT_PREFIX");
      if (optionValue != null) {
         clientPrefix = optionValue;
      }
      optionValue = System.getenv("SSHS_USERNAME");
      if (optionValue != null) {
         username = optionValue;
      }
      optionValue = System.getenv("SSHS_PASSWORD");
      if (optionValue != null) {
         password = optionValue;
      }
      optionValue = System.getenv("SSHS_WEATHER_URL");
      if (optionValue != null) {
         weatherUrl = optionValue;
      }
   }

   private static void parseCommandLine(String[] args) throws ParseException {
      // Create the command line parser
      CommandLineParser parser = new BasicParser();

      // Create the command line options
      Options commandLineOptions = new Options();
      commandLineOptions.addOption("b", brokerUrlOption, true, "set the broker url" );
      commandLineOptions.addOption("c", clientPrefixOption, true, "set the client prefix" );
      commandLineOptions.addOption("u", usernameOption, true, "set the broker username" );
      commandLineOptions.addOption("p", passwordOption, true, "set the broker password" );
      commandLineOptions.addOption("t", topicOption, true, "set the broker topic" );
      commandLineOptions.addOption("w", weatherUrlOption, true, "set the weather url" );
      commandLineOptions.addOption("m", modelTrainingOption, false, "enable the training" );
      commandLineOptions.addOption("h", helpOption, false, "print help" );

      try {
         logger.info("Parse the command line arguments");
         CommandLine commandLine = parser.parse(commandLineOptions, args );

         if (commandLine.hasOption(helpOption)) {
            printHelp(commandLineOptions);
            System.exit(0);
         }

         if (commandLine.hasOption(brokerUrlOption)) {
            brokerUrl = commandLine.getOptionValue(brokerUrlOption);
         }
         if (commandLine.hasOption(clientPrefixOption)) {
            clientPrefix = commandLine.getOptionValue(clientPrefixOption);
         }
         if (commandLine.hasOption(usernameOption)) {
            username = commandLine.getOptionValue(usernameOption);
         }
         if (commandLine.hasOption(passwordOption)) {
            password = commandLine.getOptionValue(passwordOption);
         }
         if (commandLine.hasOption(topicOption)) {
            topic = commandLine.getOptionValue(topicOption);
         }
         if (commandLine.hasOption(modelTrainingOption)) {
            modelTraining = Boolean.parseBoolean(commandLine.getOptionValue(modelTrainingOption));
         }
         if (commandLine.hasOption(weatherUrlOption)) {
            weatherUrl = commandLine.getOptionValue(weatherUrlOption);
         }
      } catch (UnrecognizedOptionException e) {
         printHelp(commandLineOptions);
         System.exit(-1);
      }
   }

   private static void printHelp(Options commandLineOptions) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp( "java " + SparkApp.class.getName() + " [OPTIONS]", commandLineOptions );
   }
}
