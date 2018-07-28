The server uses a computer with OpenShift to deploy EnMasse and Apache Spark. The sources of the project are available on GitHub: https://github.com/brusdev/smart-solar-heating-system/tree/master/server

![Server Photo](../images/server_photo.png)

## OpenShift
OpenShift is a computer software product from Red Hat for container-based software deployment and management. It is a supported distribution of Kubernetes using Docker containers and DevOps tools for accelerated application development. OpenShift may be executed locally by running a single-node OpenShift cluster inside a VM using minishift but it requires a hypervisor to start the virtual machine on which the OpenShift cluster is provisioned. The full installation documentation may be found here: https://docs.openshift.org/latest/minishift/getting-started/installing.html.
You need at least 6GB of RAM for your minishift instance since we're running both EnMasse and Spark on a local OpenShift cluster.

```
minishift start --cpus 2 --memory 6144
```

Once this command completes, the OpenShift cluster should be ready to use.

## EnMasse
EnMasse is an open source messaging platform, with focus on scalability and performance. EnMasse can run on your own infrastructure or in the cloud, and simplifies the deployment of messaging infrastructure and promotes open standards like AMQP and MQTT etc. and aims to provide support for other protocols as well. To deploy EnMasse to OpenShift download the latest release and unpack:

```
tar xvf enmasse-0.13.2.tgz
```

The relase bundle contains OpenShift templates as well as a deployment script for deploying EnMasse.

```
deploy-openshift.sh
```

## Apache Spark
Spark is a fast and general cluster computing system for Big Data. It provides high-level APIs in Scala, Java, Python, and R, and an optimized engine that supports general computation graphs for data analysis. It also supports a rich set of higher-level tools including Spark SQL for SQL and DataFrames, MLlib for machine learning, GraphX for graph processing, and Spark Streaming for stream processing. To deploy Spark to OpenShift you may use Oshinko a project that covers several individual applications which all focus on the goal of deploying and managing Apache Spark clusters on Red Hat OpenShift and OpenShift Origin. You can find the full document installation here: https://radanalytics.io/get-started. First, install all the Oshinko resources into your project:

```
oc create -f https://radanalytics.io/resources.yaml
```

Second, start the Oshinko Web UI application:

```
oc new-app --template=oshinko-webui
```

To process the temperature message from the gateway i create a JavaReceiverInputDStream using AMQPUtils.createStream:

```Java
JavaReceiverInputDStream<DeviceTemperature> receiveStream =
  AMQPUtils.createStream(ssc, host, port,
    Option.apply(username), Option.apply(password), temperatureAddress,
    message -> {

     Section section = message.getBody();
     if (section instanceof AmqpValue) {
      Object value = ((AmqpValue) section).getValue();
      DeviceTemperature deviceTemperature =
        DeviceTemperature.fromJson(value.toString());
      return new Some<>(deviceTemperature);
     } else if (section instanceof Data) {
      Binary data = ((Data)section).getValue();
      DeviceTemperature deviceTemperature =
        DeviceTemperature.fromJson(new String(data.getArray(), "UTF-8"));
      return new Some<>(deviceTemperature);
     } else {
      return null;
     }

    }, StorageLevel.MEMORY_ONLY());
```

To reduce the stream i evaluate the avarage temperature on a time windows:

```Java
// reducing the pair stream by key (device-id) for getting average temperature value
JavaPairDStream<String, Integer> avgTemperaturesByDevice = temperaturesByDevice
.mapValues((value) -> { return new Integer[] {value, 1}; })
.reduceByKeyAndWindow((x, y) -> { return new Integer[] {x[0] + y[0], x[1] + y[1]}; }, new Duration(5000), new Duration(5000))
.mapValues((z) -> { return z[0] / z[1]; });
```

To make temperature forecast i use a linear regression model with 7 features: previous boiler temperature, previous weather temperature, previous weather humidity, previous weather wind speed, current weather temperature, current weather humidity, and current weather wind speed.

```Java
model = new StreamingLinearRegressionWithSGD().setInitialWeights(Vectors.zeros(7));
```

When data arrive in a streaming fashion, it is useful to fit regression models online, updating the parameters of the model as new data arrives. spark.mllib currently supports streaming linear regression using ordinary least squares. The fitting is similar to that performed offline, except fitting occurs on each batch of data, so that the model continually updates to reflect the data from the stream.

```Java
JavaDStream<LabeledPoint> trainingData = temperaturesByDevice.map((x) -> {
 CurrentWeather cwd = owm.currentWeatherByCityId(owmCityId);
 
 ...

 return new LabeledPoint((double )x._2, Vectors.dense(new double[] {
  previousBoilerTemperature,
  previousWeatherTemperature,
  previousWeatherHumidity,
  previousWeatherWindSpeed,
  cwd.getMainData().getTemp(),
  cwd.getMainData().getHumidity(),
  cwd.getWindData().getSpeed() }));
});

// register the streams for training
model.trainOn(trainingData);
```

Then you may use the model to predict the temperature:

```Java
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

JavaDStream<Double> forecastTemperatures = model.predictOn(testData);
```
