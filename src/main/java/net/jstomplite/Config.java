package net.jstomplite;

public class Config {
  private final String host;
  private final int port;
  private final String virtualHost;
  private final String login;
  private final String password;
  private final boolean useHeartbeat;
  private final boolean useSsl;

  public Config(String host, int port, String virtualHost, String login, String password, boolean useHeartbeat,
                boolean useSsl) {
    this.host = host;
    this.port = port;
    this.virtualHost = virtualHost;
    this.login = login;
    this.password = password;
    this.useHeartbeat = useHeartbeat;
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

  public boolean useHeartbeat() {
    return useHeartbeat;
  }

  public boolean useSsl() {
    return useSsl;
  }
}
