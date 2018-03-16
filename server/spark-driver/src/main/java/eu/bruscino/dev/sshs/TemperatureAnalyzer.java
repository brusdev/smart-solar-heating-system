/*
 * Copyright 2017 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.bruscino.dev.sshs;

import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonSender;
import net.aksingh.owmjapis.core.OWM;
import net.aksingh.owmjapis.model.CurrentWeather;
import net.aksingh.owmjapis.model.HourlyWeatherForecast;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.Message;
import org.apache.spark.SparkConf;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.regression.StreamingLinearRegressionWithSGD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.amqp.AMQPUtils;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.Some;
import scala.Tuple2;

/**
 * Sample Spark driver for getting temperature values from sensor
 * analyzing them in real team as a stream providing max value in
 * the latest 5 secs
 */
public class TemperatureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TemperatureAnalyzer.class);

    private static final String APP_NAME = "TemperatureAnalyzer";
    private static final Duration BATCH_DURATION = new Duration(1000);

    private static final String CHECKPOINT_DIR = "/tmp/spark-streaming-amqp";

    private static String host = "localhost";
    private static int port = 5672;
    private static String username = null;
    private static String password = null;
    private static String temperatureAddress = "temperature";
    private static String forecastAddress = "forecast";

    private static OWM owm = null;
    private static int owmCityId = 0;

    private static StreamingLinearRegressionWithSGD model;

    public static void main(String[] args) throws InterruptedException {

        // getting AMQP messaging service connection information
        String messagingServiceHost = System.getenv("MESSAGING_SERVICE_HOST");
        if (messagingServiceHost != null) {
            host = messagingServiceHost;
        }
        String messagingServicePort = System.getenv("MESSAGING_SERVICE_PORT");
        if (messagingServicePort != null) {
            port = Integer.valueOf(messagingServicePort);
        }
        log.info("AMQP messaging service hostname {}:{}", host, port);

        // getting credentials for authentication
        username = System.getenv("SPARK_DRIVER_USERNAME");
        password = System.getenv("SPARK_DRIVER_PASSWORD");
        log.info("Credentials {}/{}", username, password);

        owm = new OWM(System.getenv("OWM_API_KEY"));
        owmCityId = Integer.parseInt(System.getenv("OWM_CITY_ID"));

        model = new StreamingLinearRegressionWithSGD().setInitialWeights(Vectors.zeros(7));


        JavaStreamingContext ssc = JavaStreamingContext.getOrCreate(CHECKPOINT_DIR, TemperatureAnalyzer::createStreamingContext);

        ssc.start();
        ssc.awaitTermination();
    }

    private static JavaStreamingContext createStreamingContext() {

        SparkConf conf = new SparkConf().setAppName(APP_NAME);
        //conf.setMaster("local[2]");
        conf.set("spark.streaming.receiver.writeAheadLog.enable", "true");

        JavaStreamingContext ssc = new JavaStreamingContext(conf, BATCH_DURATION);
        ssc.checkpoint(CHECKPOINT_DIR);

        SQLContext sqlContext = new SQLContext(ssc.sparkContext());
        SparkSession sparkSession = sqlContext.sparkSession();

        JavaReceiverInputDStream<DeviceTemperature> receiveStream =
                AMQPUtils.createStream(ssc, host, port,
                        Option.apply(username), Option.apply(password), temperatureAddress,
                        message -> {

                            Section section = message.getBody();
                            if (section instanceof AmqpValue) {
                                Object value = ((AmqpValue) section).getValue();
                                DeviceTemperature deviceTemperature = DeviceTemperature.fromJson(value.toString());
                                return new Some<>(deviceTemperature);
                            } else if (section instanceof Data) {
                                Binary data = ((Data)section).getValue();
                                DeviceTemperature deviceTemperature = DeviceTemperature.fromJson(new String(data.getArray(), "UTF-8"));
                                return new Some<>(deviceTemperature);
                            } else {
                                return null;
                            }

                        }, StorageLevel.MEMORY_ONLY());

        // from a stream with DeviceTemperature instace to a pair stream with key = device-id, value = temperature
        JavaPairDStream<String, Integer> temperaturesByDevice = receiveStream.mapToPair(deviceTemperature -> {
            return new Tuple2<>(deviceTemperature.deviceId(), deviceTemperature.temperature());
        });

        // reducing the pair stream by key (device-id) for getting average temperature value
        JavaPairDStream<String, Integer> avgTemperaturesByDevice = temperaturesByDevice
        .mapValues((value) -> { return new Integer[] {value, 1}; })
        .reduceByKeyAndWindow((x, y) -> { return new Integer[] {x[0] + y[0], x[1] + y[1]}; }, new Duration(5000), new Duration(5000))
        .mapValues((z) -> { return z[0] / z[1]; });

        JavaDStream<LabeledPoint> trainingData = temperaturesByDevice.map((x) -> {
            CurrentWeather cwd = owm.currentWeatherByCityId(owmCityId);

            Integer previousBoilerTemperature;
            Double previousWeatherTemperature;
            Integer previousWeatherHumidity;
            Double previousWeatherWindSpeed;

            //Select history tuples.
            Dataset<Row> historyDataset = sqlContext.sql("select * from history where timestamp between unix_timestamp() - 10805 AND unix_timestamp() - 10795");

            if (historyDataset.count() > 0) {
                Row historyRow = historyDataset.first();
                previousBoilerTemperature = historyRow.getInt(0);
                previousWeatherTemperature = historyRow.getDouble(1);
                previousWeatherHumidity = historyRow.getInt(2);
                previousWeatherWindSpeed = historyRow.getDouble(3);
            } else {
                previousBoilerTemperature = 0;
                previousWeatherTemperature = 0d;
                previousWeatherHumidity = 0;
                previousWeatherWindSpeed = 0d;
            }

            return new LabeledPoint((double )x._2, Vectors.dense(new double[] {
                previousBoilerTemperature,
                previousWeatherTemperature,
                previousWeatherHumidity,
                previousWeatherWindSpeed,
                cwd.getMainData().getTemp(),
                cwd.getMainData().getHumidity(),
                cwd.getWindData().getSpeed() }));
        });

        JavaDStream<Vector> testData = temperaturesByDevice.map((x) -> {
            CurrentWeather cwd = owm.currentWeatherByCityId(owmCityId);
            HourlyWeatherForecast hwf = owm.hourlyWeatherForecastByCityId(owmCityId);
            
            return Vectors.dense(new double[] { x._2,
                cwd.getMainData().getTemp(),
                cwd.getMainData().getHumidity(),
                cwd.getWindData().getSpeed(),
                hwf.getDataList().get(0).getMainData().getTemp(),
                hwf.getDataList().get(0).getMainData().getHumidity(),
                hwf.getDataList().get(0).getWindData().getSpeed() });
        });

        // register the streams for training
        model.trainOn(trainingData);

        // register the streams for testing
        JavaDStream<Double> forecastTemperatures = model.predictOn(testData);

        Broadcast<String> messagingHost = ssc.sparkContext().broadcast(host);
        Broadcast<Integer> messagingPort = ssc.sparkContext().broadcast(port);
        Broadcast<String> driverUsername = ssc.sparkContext().broadcast(username);
        Broadcast<String> driverPassword = ssc.sparkContext().broadcast(password);

        forecastTemperatures.foreachRDD(rdd -> {

            rdd.foreach(record -> {

                // building a DeviceTemperature instance from the pair key = device-id, value = temperature
                DeviceTemperature deviceTemperature = new DeviceTemperature("boiler", record.intValue());

                Vertx vertx = Vertx.vertx();
                ProtonClient client = ProtonClient.create(vertx);

                log.info("Connecting to messaging ...");
                client.connect(messagingHost.value(), messagingPort.value(),
                        driverUsername.value(), driverPassword.value(), done -> {

                            if (done.succeeded()) {

                                log.info("... connected to {}:{}", messagingHost.value(), messagingPort.getValue());

                                ProtonConnection connection = done.result();
                                connection.open();

                                ProtonSender maxSender = connection.createSender(forecastAddress);
                                maxSender.open();

                                Message message = ProtonHelper.message();
                                message.setAddress(forecastAddress);
                                message.setBody(new Data(new Binary(deviceTemperature.toJson().toString().getBytes())));

                                log.info("Sending {} to max address ...", deviceTemperature);
                                maxSender.send(message, maxDelivery -> {

                                    log.info("... message sent");
                                    maxSender.close();

                                    connection.close();
                                    vertx.close();

                                });

                            } else {

                                log.error("Error on AMQP connection for sending", done.cause());
                                vertx.close();
                            }

                        });
            });
        });

        return ssc;
    }
}
