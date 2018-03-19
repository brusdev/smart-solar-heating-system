The gateway uses a Raspberry Pi with Eclipse Kura to run the application that controls the relay board, Eclipse Leshan to communicate with the sensor, and Eclipse Paho to communicate with the server. The sources of the project are available on GitHub: https://github.com/brusdev/smart-solar-heating-system/tree/master/gateway

![Gateway Photo](../images/gateway_photo.jpg)

## Eclipse Kura
Eclipse Kura offers a platform that can live at the boundary between the private device network and the local network, public Internet or cellular network providing a manageable and intelligent gateway for that boundary capable of running applications that can harvest locally gathered information and deliver it reliably to the cloud.
Eclipse Kura can be installed on a Raspberry Pi but it requires Raspbian, gdebi and OpenJDK. You can find the detailed instrucions on official web site: https://eclipse.github.io/kura/intro/raspberry-pi-quick-start.html
The Kura development environment may be installed on a Windows, Linux, or Mac OS. The setup instructions will be the same across OSs though each system may have unique characteristics. Eclipse Kura Development Environment consists of the following components: JVM (Java JDK SE 8 or Open JDK 8), Eclipse IDE, Kura Workspace. You can find the detailed instrucions on official web site: https://eclipse.github.io/kura/dev/kura-setup.html
The GPIOService may be used to access the gpio pins that control the relay board but to get a reference to GPIOService you need to setup a service traker.

```Java
String selectedGPIOServicePid = DEFAULT_GPIO_SERVICE_PID;
String filterString = String.format("(&(%s=%s)(kura.service.pid=%s))",
  Constants.OBJECTCLASS, GPIOService.class.getName(), selectedGPIOServicePid);
Filter filter = null;
try {
 filter = this.bundleContext.createFilter(filterString);
} catch (InvalidSyntaxException e) {
 s_logger.error("Filter setup exception ", e);
}

this.gpioServiceTrackerCustomizer = new GPIOServiceTrackerCustomizer();
this.gpioServiceTracker = new ServiceTracker<>(this.bundleContext, filter,
  this.gpioServiceTrackerCustomizer);
this.gpioServiceTracker.open();
```

## Eclipse Leshan
Eclipse Leshan is an OMA Lightweight M2M (LWM2M) implementation in Java. Leshan relies on the Eclipse Californium project for the CoAP and DTLS implementation and provides libraries which help people to develop their own Lightweight M2M server and client.
The project also provides a client, a server and a bootstrap server demonstration as an example of the Leshan API and for testing purpose.
To init the LWM2M server you have to set the local address, to load the models and register a listener.

```Java
// Prepare LWM2M server
LeshanServerBuilder builder = new LeshanServerBuilder();
builder.setLocalAddress(null, coap_port);
builder.setLocalSecureAddress(null, coaps_port);
builder.setEncoder(new DefaultLwM2mNodeEncoder());
LwM2mNodeDecoder decoder = new DefaultLwM2mNodeDecoder();
builder.setDecoder(decoder);

// Create CoAP Config
NetworkConfig coapConfig;
File configFile = new File(NetworkConfig.DEFAULT_FILE_NAME);
if (configFile.isFile()) {
 coapConfig = new NetworkConfig();
 coapConfig.load(configFile);
} else {
 coapConfig = LeshanServerBuilder.createDefaultNetworkConfig();
 coapConfig.store(configFile);
}
builder.setCoapConfig(coapConfig);

// Define model provider
List<ObjectModel> models = ObjectLoader.loadDefault();
models.addAll(ObjectLoader.loadDdfResources("/models/", modelPaths));
LwM2mModelProvider modelProvider = new StaticModelProvider(models);
builder.setObjectModelProvider(modelProvider);

// Create and start LWM2M server
lwServer = builder.build();
s_logger.info("Bundle " + APP_ID + " LeshanServer build!");

sensorRegistrationListener = new SensorRegistrationListener();
lwServer.getRegistrationService().addListener(sensorRegistrationListener);

try {
 s_logger.info("Bundle " + APP_ID + " LeshanServer start!");
 lwServer.start();
} catch (Exception e) {
 s_logger.error(e.toString());
 e.printStackTrace();
 lwServer.stop();
}
```

## Eclipse Paho
Eclipse Paho is a set of scalable open-source implementations of open and standard messaging protocols aimed at new, existing, and emerging applications for Machine-to-Machine (M2M) and Internet of Things (IoT). To init the MQTT client you need to set the broker, the username and the password.

```Java
MemoryPersistence persistence = new MemoryPersistence();
mqttClient = new MqttClient(mqttBroker, APP_ID, persistence);
MqttConnectOptions connOpts = new MqttConnectOptions();
connOpts.setCleanSession(true);
connOpts.setUserName(mqttUsername);
connOpts.setPassword(mqttPassword.toCharArray());
s_logger.info("MqttClient.Connecting to broker: " + mqttBroker);
mqttClient.connect(connOpts);
s_logger.info("MqttClient.Connected");
```
