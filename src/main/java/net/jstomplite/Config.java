package net.jstomplite;

public class Config {
  private final String host;

  private final int port;

  private final String virtualHost;

  private final String login;

  private final String password;

  /*
  This is the client side heart beat interval the server can expect, server closes connection if not fulfilled.
  Note: the the stomp client itself will not deliver heart beart, this must be done by user sending messages.
  Server side heart beat not supported.
  0 for no heart beat.
  */
  private final boolean useSsl;

  private final int heartbeatMs;

  public Config(String host, int port, String virtualHost, String login, String password, int heartbeatMs,
                boolean useSsl) {
    this.host = host;
    this.port = port;
    this.virtualHost = virtualHost;
    this.login = login;
    this.password = password;
    this.heartbeatMs = heartbeatMs;
    this.useSsl = useSsl;
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
}
