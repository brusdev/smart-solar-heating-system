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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttBuilder {

   private String brokerUrl = "tcp://127.0.0.1:1883";
   private String clientId = "client" + System.currentTimeMillis() / 1000;
   private String username = null;
   private String password = null;
   private boolean cleanSession = true;
   private boolean checkSSLPeers = true;
   private boolean checkSSLHostname = true;
   private MqttClientPersistence persistence = new MemoryPersistence();

   public String getBrokerUrl() {
      return brokerUrl;
   }

   public MqttBuilder setBrokerUrl(String brokerUrl) {
      this.brokerUrl = brokerUrl;
      return this;
   }

   public String getClientId() {
      return clientId;
   }

   public MqttBuilder setClientId(String clientId) {
      this.clientId = clientId;
      return this;
   }

   public String getUsername() {
      return username;
   }

   public MqttBuilder setUsername(String username) {
      this.username = username;
      return this;
   }

   public String getPassword() {
      return password;
   }

   public MqttBuilder setPassword(String password) {
      this.password = password;
      return this;
   }

   public boolean isCleanSession() {
      return cleanSession;
   }

   public MqttBuilder setCleanSession(boolean cleanSession) {
      this.cleanSession = cleanSession;
      return this;
   }

   public boolean isCheckSSLPeers() {
      return checkSSLPeers;
   }

   public MqttBuilder setCheckSSLPeers(boolean checkSSLPeers) {
      this.checkSSLPeers = checkSSLPeers;
      return this;
   }

   public boolean isCheckSSLHostname() {
      return checkSSLHostname;
   }

   public MqttBuilder setCheckSSLHostname(boolean checkSSLHostname) {
      this.checkSSLHostname = checkSSLHostname;
      return this;
   }

   public MqttClientPersistence getPersistence() {
      return persistence;
   }

   public MqttBuilder setPersistence(MqttClientPersistence persistence) {
      this.persistence = persistence;
      return this;
   }

   public MqttClient createClient() throws MqttException {
      return new MqttClient(brokerUrl, clientId, persistence);
   }

   public MqttConnectOptions createConnectOptions() throws NoSuchAlgorithmException, KeyManagementException {
      MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();

      mqttConnectOptions.setCleanSession(cleanSession);
      mqttConnectOptions.setUserName(username);
      mqttConnectOptions.setPassword(password.toCharArray());
      mqttConnectOptions.setHttpsHostnameVerificationEnabled(checkSSLHostname);

      if (!checkSSLPeers) {
         SSLContext sslContext = SSLContext.getInstance("SSL");
         sslContext.init(null, new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }

            @Override
            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }

            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
         } }, new java.security.SecureRandom());

         mqttConnectOptions.setSocketFactory(sslContext.getSocketFactory());
      }

      return mqttConnectOptions;
   }
}
