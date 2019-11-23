package eu.bruscino.dev.sshs.gateway;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.eclipse.kura.gpio.GPIOService;
import org.eclipse.kura.gpio.KuraGPIOPin;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
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
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Executor {
	
	private static final Logger logger = LoggerFactory.getLogger(Executor.class);

	private static final String mqtt_broker_property= "mqtt.broker";
	private static final String mqtt_username_property= "mqtt.username";
	private static final String mqtt_password_property= "mqtt.password";
	private static final String mqtt_topic_property= "mqtt.topic";
	private static final String boiler_switch_pin_property= "boiler.switch.pin";
	private static final String boiler_threshold_property= "boiler.threshold";
	private static final String utility_switch_pin_property= "utility.switch.pin";
	private static final String utility_threshold_property= "utility.threshold";
	
    private static final int coap_port = 5683;
    
    private GPIOService gpioService;
    private KuraGPIOPin boilerSwitchPin;
    private int boilerSwitchPinTerminal = 20;
    private KuraGPIOPin utilitySwitchPin;
    private int utilitySwitchPinTerminal = 21;
    
	private LeshanServer lwServer;
	
	private MqttClient mqttClient;
	private Object mqttClientLock = new Object();
	private MqttConnectOptions mqttConnectOptions;
	private String mqttBroker = "ssl://192.168.10.16:8883";
	private String mqttClientId = "gatewayClient";
	private String mqttUsername = "brusdev";
	private String mqttPassword = "password";
	private String mqttTopic = "sshs/gateway";
	
	private Object runnerLock = new Object();
	private Thread runnerThread;
	private boolean running = false;
	private double tankTemperature = 0;
	private double tankForecast = 0;
	private boolean boilerEnabled = false;
	private double boilerThreshold = 45;
	private boolean utilityEnabled = false;
	private double utilityThreshold = 60;
	
    private ExecutorService executorService;
    
	public Executor() {
		executorService = Executors.newSingleThreadExecutor();
	}
	
    protected synchronized void bindGPIOService(final GPIOService gpioService) {
        logger.info("bindGPIOService");
        this.gpioService = gpioService;
    }

    protected synchronized void unbindGPIOService(final GPIOService gpioService) {
        logger.info("unbindGPIOService");
        this.gpioService = null;
    }
    
    protected void activate(ComponentContext componentContext) {
    	activate(componentContext, null);
    }
    
    protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
        logger.info("Activating...");
        
        if (properties != null) {
    		mqttBroker = properties.get(mqtt_broker_property).toString();
    		mqttUsername = properties.get(mqtt_username_property).toString();
    		mqttPassword = properties.get(mqtt_password_property).toString();
    		mqttTopic = properties.get(mqtt_topic_property).toString();
    		boilerSwitchPinTerminal = Integer.parseInt(properties.get(boiler_switch_pin_property).toString());
    		boilerThreshold = Integer.parseInt(properties.get(boiler_threshold_property).toString());
    		utilitySwitchPinTerminal = Integer.parseInt(properties.get(utility_switch_pin_property).toString());
    		utilityThreshold = Integer.parseInt(properties.get(utility_threshold_property).toString());
        }
        
		initPins();
		
		initMqttClient();
		
		initLwM2mServer();
		
		initRunner();
    }
    
    protected void deactivate(ComponentContext componentContext) {
        logger.info("Deactivating...");
        
        logger.info("Stop Runner");
        stopRunner();
        
        logger.info("Stop LeshanServer");
		lwServer.stop();
		
        logger.info("Close mqttClient");
		closeMqttClient();
		
        logger.info("Close pins");
        closePins();
    }
    
    
    private void initPins() {
		boilerSwitchPin = this.gpioService.getPinByTerminal(boilerSwitchPinTerminal);
		
    	try {
			boilerSwitchPin.open();
        } catch (Exception e) {
        	logger.error("Exception opening boilerSwitchPin: ", e);
        }
		
		utilitySwitchPin = this.gpioService.getPinByTerminal(utilitySwitchPinTerminal);
    	
    	try {
    		utilitySwitchPin.open();
        } catch (Exception e) {
        	logger.error("Exception opening utilitySwitchPin: ", e);
        }
    }
    
    private void setBoilerSwitchPinValue(boolean value) {
    	try {
            logger.info("Set boiler switch pin value " + value);
            boilerSwitchPin.setValue(value);
        } catch (Exception e) {
            logger.error("Exception setting boiler switch pin value: ", e);
        }
    }
    
    private void setUtilitySwitchPinValue(boolean value) {
    	try {
            logger.info("Set utility switch pin value " + value);
            utilitySwitchPin.setValue(value);
        } catch (Exception e) {
            logger.error("Exception setting utility switch pin value: ", e);
        }
    }
    
    private void closePins() {
    	try {
			boilerSwitchPin.close();
        } catch (Exception e) {
        	logger.error("Exception closing boilerSwitchPin: ", e);
        }
		
    	try {
    		utilitySwitchPin.close();
        } catch (Exception e) {
        	logger.error("Exception closing utilitySwitchPin: ", e);
        }
    }
    
    private void initLwM2mServer() {
		// Prepare LWM2M server
		LeshanServerBuilder builder = new LeshanServerBuilder();
		builder.setLocalAddress(null, coap_port);
		builder.setEncoder(new DefaultLwM2mNodeEncoder());
		builder.setDecoder(new DefaultLwM2mNodeDecoder());
		builder.setCoapConfig(LeshanServerBuilder.createDefaultNetworkConfig());

		// Define model provider
		List<ObjectModel> models = ObjectLoader.loadDefault();
		models.addAll(ObjectLoader.loadDdfResources("/models/", new String[] { "3300.xml" }));
		LwM2mModelProvider modelProvider = new StaticModelProvider(models);
		builder.setObjectModelProvider(modelProvider);

		// Create and start LWM2M server
		logger.info("Build LeshanServer");
		lwServer = builder.build();

		lwServer.getRegistrationService().addListener(new RegistrationListener() {
			@Override
			public void registered(Registration reg, Registration previousReg, Collection<Observation> previousObsersations) {
				logger.info(reg.getId() + "@" + reg.getEndpoint() + " registered");
			}
			
			@Override
			public void unregistered(Registration reg, Collection<Observation> observations, boolean expired, Registration newReg) {
				logger.info(reg.getId() + "@" + reg.getEndpoint() + " unregistered");
			}

			@Override
			public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
				logger.info(updatedReg.getId() + "@" + updatedReg.getEndpoint() + " updated");
				
				executorService.execute(new Runnable() {
					@Override
					public void run() {
						logger.info("Begin reading sensor");
						try {
							ReadRequest request = new ReadRequest(ContentFormat.fromName("TVL"), "/3300/0/5700");
							
							ReadResponse response = lwServer.send(updatedReg, request, 5000);

							LwM2mSingleResource responseContent = (LwM2mSingleResource) response.getContent();
							
							logger.info("Sensor responseContent: " + responseContent);
							
							updateTankTemperature((double)responseContent.getValue());
						} catch (Exception e) {
							logger.error("Exception reading sensor : ");
						}
						logger.info("End reading sensor");
					}
				});
			}
		});

		try {
			logger.info("Start LeshanServer");
			lwServer.start();
		} catch (Exception e) {
			logger.error("Exception starting LeshanServer: " + e);
			
			logger.info("Stop LeshanServer");
			lwServer.stop();
		}
    }
    
	private void initMqttClient() {
		try {
	        SSLContext sc = SSLContext.getInstance("SSL");
	        sc.init(null, new TrustManager[] { new X509TrustManager() {
	            @Override
	            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }

	            @Override
	            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }

	            @Override
	            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
	        } }, new java.security.SecureRandom());

	        mqttClient = new MqttClient(mqttBroker, mqttClientId, new MemoryPersistence());
	        mqttConnectOptions = new MqttConnectOptions();
	        mqttConnectOptions.setCleanSession(true);
	        mqttConnectOptions.setSocketFactory(sc.getSocketFactory());
	        mqttConnectOptions.setHttpsHostnameVerificationEnabled(false);
	        mqttConnectOptions.setUserName(mqttUsername);
	        mqttConnectOptions.setPassword(mqttPassword.toCharArray());

	        mqttClient.setCallback(new MqttCallback() {
	            @Override
	            public void connectionLost(Throwable cause) {
	            	logger.info("MQTT connection lost");
	            }

	            @Override
	            public void messageArrived(String topic, MqttMessage message) throws Exception {
	            	logger.info("MQTT message { " + message + " } arrived from " + topic);
	                
	                if (topic.equals(mqttTopic + "/tank/forecast")) {
	                	updateTankForecast(Double.parseDouble(new String(message.getPayload(), StandardCharsets.UTF_8)));
	                } else if (topic.equals(mqttTopic + "/boiler/threshold/set")) {
	                	setBoilerThreshold(Double.parseDouble(new String(message.getPayload(), StandardCharsets.UTF_8)));
	                } else if (topic.equals(mqttTopic + "/utility/threshold/set")) {
	                	setUtilityThreshold(Double.parseDouble(new String(message.getPayload(), StandardCharsets.UTF_8)));
	                }
	            }

	            @Override
	            public void deliveryComplete(IMqttDeliveryToken token) {
	            	logger.info("MQTT delivery complete" + token);
	            }
	        });
		} catch (Exception e) {
			logger.error("Exception initializing mqttClient: " + e);
		}
	}
	
	private void closeMqttClient() {
		try {
			mqttClient.close();
		} catch (Exception e) {
			logger.error("Exception Closing mqttClient: " + e);
		}
	}
	
	private void initRunner() {
		running = true;
		
		runnerThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					while(running) {
						try {
			            	logger.info("Connect mqttClient");
							mqttClient.connect(mqttConnectOptions);
						
							mqttClient.subscribe(mqttTopic + "/#");
							
							try {
								int count = 0;
								while(running) {
									synchronized (runnerLock) {
										boilerEnabled = (tankTemperature < boilerThreshold);
										setBoilerSwitchPinValue(!boilerEnabled);
										
										utilityEnabled = (tankForecast > utilityThreshold);
										setUtilitySwitchPinValue(!utilityEnabled);
										
										publishTankTemperature(tankTemperature);
										
										if (count == 5) {
											count = 0;
											
											publishBoilerEnabled(boilerEnabled);
											publishBoilerThreshold(boilerThreshold);
											
											publishUtilityEnabled(utilityEnabled);
											publishUtilityThreshold(utilityThreshold);
										} else {
											count++;
										}
									}
									
									if (running) {
						            	logger.info("Wait running inner");
										Thread.sleep(1000);
									}
								}
							} catch (Exception e) {
								logger.error("Exception running inner: " + e);
							}
						
							mqttClient.disconnect();
						} catch (Exception e) {
							logger.error("Exception running middle: " + e);
						}
						
						if (running) {
			            	logger.info("Wait running middle");
							Thread.sleep(3000);
						}
					}
				} catch (Exception e) {
					logger.error("Exception running outer: " + e);
				}
			}
		});
		runnerThread.start();
	}
	
	private void stopRunner() {
		try {
			running = false;
			runnerThread.join();
		} catch (Exception e) {
			logger.error("Exception stopping runner: " + e);
		}
	}
    
    private void updateTankTemperature(double temperature) {
		synchronized (runnerLock) {
	    	logger.info("Update tank temperature " + temperature);
			tankTemperature = temperature;
		}
    }
    
    private void updateTankForecast(double forecast) {
		synchronized (runnerLock) {
	    	logger.info("Update tank forecast " + forecast);
			tankForecast = forecast;
		}
    }

    private void setBoilerThreshold(double threshold) {
		synchronized (runnerLock) {
	    	logger.info("Set boiler threshold " + threshold);
			boilerThreshold = threshold;
		}
    }
    
    private void setUtilityThreshold(double threshold) {
		synchronized (runnerLock) {
	    	logger.info("Set utility threshold " + threshold);
			utilityThreshold = threshold;
		}
    }
    
    private void publishTankTemperature(double temperature) throws MqttPersistenceException, MqttException {
    	logger.info("Publish tank temperature " + temperature);
    	publishMqttMessage(mqttTopic + "/tank/temperature", Double.toString(temperature));
    }
    
    private void publishBoilerEnabled(boolean enabled) throws MqttPersistenceException, MqttException {
    	logger.info("Publish boiler enabled " + enabled);
    	publishMqttMessage(mqttTopic + "/boiler/enabled", Boolean.toString(enabled));
    }
    
    private void publishBoilerThreshold(double threshold) throws MqttPersistenceException, MqttException {
    	logger.info("Publish boiler threshold " + threshold);
    	publishMqttMessage(mqttTopic + "/boiler/threshold", Double.toString(threshold));
    }
    
    private void publishUtilityEnabled(boolean enabled) throws MqttPersistenceException, MqttException {
    	logger.info("Publish utility enabled " + enabled);
    	publishMqttMessage(mqttTopic + "/utility/enabled", Boolean.toString(enabled));
    }
    
    private void publishUtilityThreshold(double threshold) throws MqttPersistenceException, MqttException {
    	logger.info("Publish utility threshold " + threshold);
    	publishMqttMessage(mqttTopic + "/utility/threshold", Double.toString(threshold));
    }
    
    private void publishMqttMessage(String topic, String payload) throws MqttPersistenceException, MqttException {
    	synchronized (mqttClientLock) {
        	logger.info("Publish MQTT message{ " + payload + " } on " + topic);
    		mqttClient.publish(topic, payload.getBytes(StandardCharsets.UTF_8) , 1, false);
    	}
    }
}
