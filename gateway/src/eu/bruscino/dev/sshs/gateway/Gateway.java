package eu.bruscino.dev.sshs.gateway;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.gpio.GPIOService;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.impl.LeshanServer;
import org.eclipse.leshan.server.model.LwM2mModelProvider;
import org.eclipse.leshan.server.model.StaticModelProvider;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gateway implements ConfigurableComponent {

	private static final Logger s_logger = LoggerFactory.getLogger(Gateway.class);

	private static final String APP_ID = "eu.bruscino.dev.sshs.gateway.Gateway";

	private static final String mqtt_broker_property= "mqtt.broker";
	private static final String mqtt_username_property= "mqtt.username";
	private static final String mqtt_password_property= "mqtt.password";
	private static final String mqtt_topic_temperature_property= "mqtt.topic.temparature";
	private static final String mqtt_topic_forecast_property= "mqtt.topic.forecast";
	private static final String switch_pin_boiler_property= "switch.pin.boiler";
	private static final String switch_pin_utility_property= "switch.pin.utility";
	private static final String temperature_threshold_boiler_property= "temperature.threshold.boiler";
	private static final String temperature_threshold_utility_property= "temperature.threshold.utility";
	
	private static final int coap_port = 5683;
	private static final int coaps_port = 5684;

	private final static String[] modelPaths = new String[] { "31024.xml",

			"10241.xml", "10242.xml", "10243.xml", "10244.xml", "10245.xml", "10246.xml", "10247.xml", "10248.xml",
			"10249.xml", "10250.xml",

			"2048.xml", "2049.xml", "2050.xml", "2051.xml", "2052.xml", "2053.xml", "2054.xml", "2055.xml", "2056.xml",
			"2057.xml",

			"3200.xml", "3201.xml", "3202.xml", "3203.xml", "3300.xml", "3301.xml", "3302.xml", "3303.xml", "3304.xml",
			"3305.xml", "3306.xml", "3308.xml", "3310.xml", "3311.xml", "3312.xml", "3313.xml", "3314.xml", "3315.xml",
			"3316.xml", "3317.xml", "3318.xml", "3319.xml", "3320.xml", "3321.xml", "3322.xml", "3323.xml", "3324.xml",
			"3325.xml", "3326.xml", "3327.xml", "3328.xml", "3329.xml", "3330.xml", "3331.xml", "3332.xml", "3333.xml",
			"3334.xml", "3335.xml", "3336.xml", "3337.xml", "3338.xml", "3339.xml", "3340.xml", "3341.xml", "3342.xml",
			"3343.xml", "3344.xml", "3345.xml", "3346.xml", "3347.xml", "3348.xml",

			"Communication_Characteristics-V1_0.xml",

			"LWM2M_Lock_and_Wipe-V1_0.xml", "LWM2M_Cellular_connectivity-v1_0.xml",
			"LWM2M_APN_connection_profile-v1_0.xml", "LWM2M_WLAN_connectivity4-v1_0.xml",
			"LWM2M_Bearer_selection-v1_0.xml", "LWM2M_Portfolio-v1_0.xml", "LWM2M_DevCapMgmt-v1_0.xml",
			"LWM2M_Software_Component-v1_0.xml", "LWM2M_Software_Management-v1_0.xml",

			"Non-Access_Stratum_NAS_configuration-V1_0.xml" };

	private static final String DEFAULT_GPIO_SERVICE_PID = "org.eclipse.kura.gpio.GPIOService";
	
	private BundleContext bundleContext;
	private GPIOService gpioService;
	private ServiceTrackerCustomizer<GPIOService, GPIOService> gpioServiceTrackerCustomizer;
	private ServiceTracker<GPIOService, GPIOService> gpioServiceTracker;
	private String switchPinBoiler;
	private String switchPinUtility;
	private int temperatureThresholdBoiler;
	private int temperatureThresholdUtility;
	    
	private LeshanServer lwServer;
	private SensorRegistrationListener sensorRegistrationListener;
	
	private MqttClient mqttClient;
	private String mqttBroker;
	private String mqttUsername;
	private String mqttPassword;
	private String mqttTopicTemperature;
	private String mqttTopicForecast;

    private final class GPIOServiceTrackerCustomizer implements ServiceTrackerCustomizer<GPIOService, GPIOService> {

        @Override
        public GPIOService addingService(final ServiceReference<GPIOService> reference) {
        	Gateway.this.gpioService = Gateway.this.bundleContext.getService(reference);
            return Gateway.this.gpioService;
        }

        @Override
        public void modifiedService(final ServiceReference<GPIOService> reference, final GPIOService service) {
        	Gateway.this.gpioService = Gateway.this.bundleContext.getService(reference);
        }

        @Override
        public void removedService(final ServiceReference<GPIOService> reference, final GPIOService service) {
        	Gateway.this.gpioService = null;
        }
    }
    
    private final class SensorRegistrationListener implements RegistrationListener {

		@Override
		public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
			s_logger.info("updated");

			new Thread(new Runnable() {

				@Override
				public void run() {
					s_logger.info("Read.Begin");
					try {
						String target = "/3300/0/5700";
						Registration registration = updatedReg;
						if (registration != null) {
							// get content format
							String contentFormatParam = "TVL";
							ContentFormat contentFormat = contentFormatParam != null
									? ContentFormat.fromName(contentFormatParam.toUpperCase())
									: null;

							// create & process request
							ReadRequest request = new ReadRequest(contentFormat, target);
							ReadResponse response = lwServer.send(registration, request, 5000);

							LwM2mSingleResource responseContent = (LwM2mSingleResource) response
									.getContent();
							
							s_logger.info(responseContent.toString());
							
							if ((int)responseContent.getValue() > temperatureThresholdBoiler) {
								gpioService.getPinByName(switchPinBoiler).setValue(false);
					        } else {
								gpioService.getPinByName(switchPinBoiler).setValue(false);
					        }

							try {
								JsonObject jsonMessage = Json.createObjectBuilder()
										.add("device-id", registration.getEndpoint())
										.add("temperature", (int)responseContent.getValue()).build();

								String messageContent = jsonMessage.toString();

								s_logger.info("Publishing message: " + messageContent);
								MqttMessage message = new MqttMessage(messageContent.getBytes());
								message.setQos(2);

								mqttClient.publish(mqttTopicTemperature, message);
								s_logger.info("Message published");
							} catch (MqttException me) {
								s_logger.info("reason " + me.getReasonCode());
								s_logger.info("msg " + me.getMessage());
								s_logger.info("loc " + me.getLocalizedMessage());
								s_logger.info("cause " + me.getCause());
								s_logger.info("excep " + me);
								me.printStackTrace();
							}
						} else {
							s_logger.info("Bad updatedReg");
						}
					} catch (Exception e) {
						// s_logger.error(e.toString());
						StringWriter sw = new StringWriter();
						e.printStackTrace(new PrintWriter(sw));
						String exceptionAsString = sw.toString();
						s_logger.error(exceptionAsString);
					}
					s_logger.info("Read.End");
				}
			}).start();
		}

		@Override
		public void unregistered(Registration reg, Collection<Observation> observations, boolean expired,
				Registration newReg) {
			s_logger.info("unregistered");
		}

		@Override
		public void registered(Registration reg, Registration previousReg,
				Collection<Observation> previousObsersations) {
			s_logger.info("registered");
		}
    }
    
    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {

		s_logger.info("Bundle " + APP_ID + " has started!");

		try {
			bundleContext = componentContext.getBundleContext();			
			
			mqttBroker = properties.get(mqtt_broker_property).toString();
			mqttUsername = properties.get(mqtt_username_property).toString();
			mqttPassword = properties.get(mqtt_password_property).toString();
			mqttTopicTemperature = properties.get(mqtt_topic_temperature_property).toString();
			mqttTopicForecast = properties.get(mqtt_topic_forecast_property).toString();
			switchPinBoiler = properties.get(switch_pin_boiler_property).toString();
			switchPinUtility = properties.get(switch_pin_utility_property).toString();
			temperatureThresholdBoiler = Integer.parseInt(properties.get(temperature_threshold_boiler_property).toString());
			temperatureThresholdUtility = Integer.parseInt(properties.get(temperature_threshold_utility_property).toString());
			
			initMqttClient();
			
			initGPIOServiceTracking();
			
			initLwM2mServer();

		} catch (Throwable t) {
			s_logger.error(t.toString());
		}
	}

	protected void deactivate(ComponentContext componentContext) {
		s_logger.info("Bundle " + APP_ID + " MqttClient disconnect!");
		
		try {
			mqttClient.disconnect();
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		this.gpioServiceTracker.close();
		
		s_logger.info("Bundle " + APP_ID + " LeshanServer stop!");
		lwServer.stop();

		s_logger.info("Bundle " + APP_ID + " has stopped!");

	}
	
    private void initGPIOServiceTracking() {
        String selectedGPIOServicePid = DEFAULT_GPIO_SERVICE_PID;
        String filterString = String.format("(&(%s=%s)(kura.service.pid=%s))", Constants.OBJECTCLASS,
                GPIOService.class.getName(), selectedGPIOServicePid);
        Filter filter = null;
        try {
            filter = this.bundleContext.createFilter(filterString);
        } catch (InvalidSyntaxException e) {
        	s_logger.error("Filter setup exception ", e);
        }
        
        this.gpioServiceTrackerCustomizer = new GPIOServiceTrackerCustomizer();
        this.gpioServiceTracker = new ServiceTracker<>(this.bundleContext, filter, this.gpioServiceTrackerCustomizer);
        this.gpioServiceTracker.open();
    }
    
	private void initMqttClient() throws MqttException {
		MemoryPersistence persistence = new MemoryPersistence();
		mqttClient = new MqttClient(mqttBroker, APP_ID, persistence);
		MqttConnectOptions connOpts = new MqttConnectOptions();
		connOpts.setCleanSession(true);
		connOpts.setUserName(mqttUsername);
		connOpts.setPassword(mqttPassword.toCharArray());
		s_logger.info("MqttClient.Connecting to broker: " + mqttBroker);
		mqttClient.connect(connOpts);
		s_logger.info("MqttClient.Connected");
		
		mqttClient.setCallback(new MqttCallback() {
			
			@Override
			public void messageArrived(String topic, MqttMessage message) throws Exception {
				JsonReader reader = Json.createReader(
						new StringReader(new String(message.getPayload())));
		         
		        JsonObject switchObject = reader.readObject();
		         
		        reader.close();

		        String switchPin;
		        String deviceId = switchObject.getString("device-id");
		        int temperature = switchObject.getInt("temperature");
		        
		        if (temperature > temperatureThresholdUtility) {
					gpioService.getPinByName(switchPinUtility).setValue(false);
		        } else {
					gpioService.getPinByName(switchPinUtility).setValue(false);
		        }
			}
			
			@Override
			public void deliveryComplete(IMqttDeliveryToken token) {
			}
			
			@Override
			public void connectionLost(Throwable cause) {
			}
		});
		
		mqttClient.subscribe(mqttTopicForecast);
	}
	
    private void initLwM2mServer() {
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
    }
	
}