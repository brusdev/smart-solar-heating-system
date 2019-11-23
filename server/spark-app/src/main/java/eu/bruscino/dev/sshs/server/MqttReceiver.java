/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.bruscino.dev.sshs.server;

import java.nio.charset.StandardCharsets;

import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming.receiver.Receiver;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttReceiver extends Receiver {

   private String brokerUrl;
   private String clientId;
   private String username;
   private String password;
   private String topic;

   public MqttReceiver(StorageLevel storageLevel,
                       String brokerUrl,
                       String clientId,
                       String username,
                       String password,
                       String topic) {
      super(storageLevel);
      this.brokerUrl = brokerUrl;
      this.clientId = clientId;
      this.username = username;
      this.password = password;
      this.topic = topic;
   }

   @Override
   public void onStart() {
      new Thread(this::receive).start();
   }

   @Override
   public void onStop() {

   }

   private void receive() {
      try {
         MqttBuilder mqttBuilder = new MqttBuilder().setBrokerUrl(brokerUrl).
            setClientId(clientId).setPersistence(new MemoryPersistence()).
            setUsername(username).setPassword(password).
            setCheckSSLPeers(false).setCheckSSLHostname(false);

         MqttClient mqttClient = mqttBuilder.createClient();
         MqttConnectOptions mqttConnectOptions = mqttBuilder.createConnectOptions();

         mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
               restart("Connection lost", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
               store(new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
         });

         mqttClient.connect(mqttConnectOptions);

         mqttClient.subscribe(topic);
      } catch (Exception e) {
         restart("Exception on receive", e);
      }
   }
}
