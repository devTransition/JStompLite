/**
 * Copyright 2014 hp.weber GmbH & Co secucard KG (www.secucard.com)
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.jstomplite;

public class Config {
  private final String host;
  private final int port;
  private final String virtualHost;
  private final String login;
  private final String password;
  private final int socketTimeoutSec;
  private final int receiptTimeoutSec;
  private final int connectionTimeoutSec;

  /*
  This is the client side heart beat interval the server can expect, server closes connection if not fulfilled.
  Note: the the stomp client itself will not deliver heart beart, this must be done by user sending messages.
  Server side heart beat not supported.
  0 for no heart beat.
  */
  private final boolean useSsl;

  private final int heartbeatMs;

  public Config(String host, int port, String virtualHost, String login, String password, int heartbeatMs,
                boolean useSsl, int socketTimeoutSec, int receiptTimeoutSec, int connectionTimeoutSec) {
    this.host = host;
    this.port = port;
    this.virtualHost = virtualHost;
    this.login = login;
    this.password = password;
    this.heartbeatMs = heartbeatMs;
    this.useSsl = useSsl;
    this.socketTimeoutSec = socketTimeoutSec;
    this.receiptTimeoutSec = receiptTimeoutSec;
    this.connectionTimeoutSec = connectionTimeoutSec;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getVirtualHost() {
    return virtualHost;
  }

  public String getLogin() {
    return login;
  }

  public String getPassword() {
    return password;
  }

  public int getHeartbeatMs() {
    return heartbeatMs;
  }

  public boolean useSsl() {
    return useSsl;
  }

  public int getSocketTimeoutSec() {
    return socketTimeoutSec;
  }

  public int getReceiptTimeoutSec() {
    return receiptTimeoutSec;
  }

  public int getConnectionTimeoutSec() {
    return connectionTimeoutSec;
  }

  @Override
  public String toString() {
    return "Config{" +
        "host='" + host + '\'' +
        ", port=" + port +
        ", virtualHost='" + virtualHost + '\'' +
        ", login='" + login + '\'' +
        ", password='" + password + '\'' +
        ", socketTimeoutSec=" + socketTimeoutSec +
        ", receiptTimeoutSec=" + receiptTimeoutSec +
        ", connectionTimeoutSec=" + connectionTimeoutSec +
        ", useSsl=" + useSsl +
        ", heartbeatMs=" + heartbeatMs +
        '}';
  }
}
