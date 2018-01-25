/*****
 ESP8266 Led via lwm2m
 Blink the blue LED on the ESP-01 or the seperate red or blue led on the nodeMCU module
 This example code is in the public domain.
 
 The blue LED on the ESP-01 module is connected to GPIO1 
 (which is also the TXD pin; so we cannot use Serial.print() at the same time)
*****/

#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>

#include <time.h>

#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <OneWire.h>
#include <DallasTemperature.h>

#include "wakaama_simple_client.h"
#include "wakaama_network.h"
#include "wakaama_object_utils.h"

#include "led_object.h"

#define LWM2M_SERVER_ADDR "coap://192.168.10.189"

#define ONE_WIRE_BUS D3  // DS18B20 pin
#define ONE_WIRE_MAX_DEV 15 //The maximum number of devices

OneWire oneWire(ONE_WIRE_BUS);
DallasTemperature DS18B20(&oneWire);
int numberOfDevices; //Number of temperature devices found
DeviceAddress devAddr[ONE_WIRE_MAX_DEV];  //An array device temperature sensors
float tempDev[ONE_WIRE_MAX_DEV]; //Saving the last measurement of temperature
float tempDevLast[ONE_WIRE_MAX_DEV]; //Previous temperature measurement
long lastTemp; //The last measurement
const int durationTemp = 5000; //The frequency of temperature measurement

//------------------------------------------
//Convert device id to String
String GetAddressToString(DeviceAddress deviceAddress){
  String str = "";
  for (uint8_t i = 0; i < 8; i++){
    if( deviceAddress[i] < 16 ) str += String(0, HEX);
    str += String(deviceAddress[i], HEX);
  }
  return str;
}

//Setting the temperature sensor
void SetupDS18B20(){
  DS18B20.begin();

  Serial.print("Parasite power is: "); 
  if( DS18B20.isParasitePowerMode() ){ 
    Serial.println("ON");
  }else{
    Serial.println("OFF");
  }
  
  numberOfDevices = DS18B20.getDeviceCount();
  Serial.print( "Device count: " );
  Serial.println( numberOfDevices );

  lastTemp = millis();
  DS18B20.requestTemperatures();

  // Loop through each device, print out address
  for(int i=0;i<numberOfDevices; i++){
    // Search the wire for address
    if( DS18B20.getAddress(devAddr[i], i) ){
      //devAddr[i] = tempDeviceAddress;
      Serial.print("Found device ");
      Serial.print(i, DEC);
      Serial.print(" with address: " + GetAddressToString(devAddr[i]));
      Serial.println();
    }else{
      Serial.print("Found ghost device at ");
      Serial.print(i, DEC);
      Serial.print(" but could not detect address. Check power and cabling");
    }

    //Get resolution of DS18b20
    Serial.print("Resolution: ");
    Serial.print(DS18B20.getResolution( devAddr[i] ));
    Serial.println();

    //Read temperature from DS18b20
    float tempC = DS18B20.getTempC( devAddr[i] );
    Serial.print("Temp C: ");
    Serial.println(tempC);
  }
}

//Loop measuring the temperature
void TempLoop(long now){
  if( now - lastTemp > durationTemp ){ //Take a measurement at a fixed time (durationTemp = 5000ms, 5s)
    for(int i=0; i<numberOfDevices; i++){
      float tempC = DS18B20.getTempC( devAddr[i] ); //Measuring temperature in Celsius
        Serial.print("TempLoop C: ");
        Serial.println(tempC);
      tempDev[i] = tempC; //Save the measured value to the array
    }
    DS18B20.setWaitForConversion(false); //No waiting for measurement
    DS18B20.requestTemperatures(); //Initiate the temperature measurement
    lastTemp = millis();  //Remember the last time measurement
  }
}


lwm2m_context_t * client_context;
void setup() {
    delay(3000);

    printf("loop heap size: %u\n", ESP.getFreeHeap());

    Serial.begin(9600);

    WiFi.begin("BrusNet", "dommiccargiafra");

    Serial.println();
    Serial.print("Connecting");
    while (WiFi.status() != WL_CONNECTED)
    {
        delay(500);
        Serial.print(".");
    }

    Serial.println("success!");

    printf("loop heap size: %u\n", ESP.getFreeHeap());

    delay(3000);
    
    Serial.print("IP Address is: ");
    Serial.println(WiFi.localIP());




  
    device_instance_t * device_data = lwm2m_device_data_get();
    device_data->manufacturer = "test manufacturer";
    device_data->model_name = "test model";
    device_data->device_type = "led";
    device_data->firmware_ver = "1.0";
    device_data->serial_number = "140234-645235-12353";
    client_context = lwm2m_client_init("testClient");
    if (client_context == 0)
    {
        printf("Failed to initialize wakaama\n");
        return;
    }

    // Create object
    lwm2m_object_t* test_object = lwm2m_object_create(5850, true, led_object_get_meta());
    lwm2m_object_instances_add(test_object, led_object_create_instances());
    lwm2m_add_object(client_context, test_object);

    // Initialize the BUILTIN_LED pin as an output
    pinMode(BUILTIN_LED, OUTPUT);     

    // Wait for network to connect

    // Init lwm2m network
    uint8_t bound_sockets = lwm2m_network_init(client_context, NULL);
    if (bound_sockets == 0)
        printf("Failed to open socket\n");
        
    lwm2m_add_server(123, LWM2M_SERVER_ADDR, 100, false, NULL, NULL, 0);


 //Setup DS18b20 temperature sensor
  SetupDS18B20();
}

void loop() {
    time_t tv_sec;

    //print_state(client_context);

    uint8_t result = lwm2m_step(client_context, &tv_sec);
    if (result == COAP_503_SERVICE_UNAVAILABLE)
        printf("No server found so far\n");
    else if (result != 0)
        printf("lwm2m_step() failed: 0x%X\n", result);

    lwm2m_network_native_sock(client_context, 0);
    lwm2m_network_process(client_context);

    long t = millis();
    TempLoop( t );
  }
 
