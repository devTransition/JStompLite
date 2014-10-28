package net.jstomplite;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class StompClient {
  private final Config config;
  private Socket socket;
  private Thread receiver;
  private Thread watchdog;
  private boolean isConnected;
  private long lastReceived;
  private int heartbeatMs = DEFAULT_HEARTBEAT_MS;

  public static final int DEFAULT_HEARTBEAT_MS = 5000;

  public static final String CONNECT = "CONNECT";
  public static final String DISCONNECT = "DISCONNECT";
  public static final String DISCONNECTED = "DISCONNECTED"; // just internal usage, no stomp command
  public static final String CONNECTED = "CONNECTED";
  public static final String RECEIPT = "RECEIPT";
  public static final String MESSAGE = "MESSAGE";
  public static final String ERROR = "ERROR";
  public static final String SEND = "SEND";
  public static final String ACK = "ACK";
  public static final String SUBSCRIBE = "SUBSCRIBE";
  public static final String UNSUBSCRIBE = "UNSUBSCRIBE";
  public static final String ABORT = "ABORT";
  public static final String COMMIT = "COMMIT";
  public static final String BEGIN = "BEGIN";

  private final static Logger LOG = Logger.getLogger(StompClient.class.getName());

  public StompClient(final Config config) throws IOException {
    this.config = config;
  }

  private void startReceiving() throws IOException {
    isConnected = false;
    lastReceived = 0;

    if (config.useHeartbeat()) {
      stopThread(watchdog);
    }
    stopThread(receiver);

    if (socket == null || socket.isClosed()) {
      SocketFactory socketFactory = config.useSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
      socket = socketFactory.createSocket(config.getHost(), config.getPort());
    }

    if (!socket.isConnected()) {
      socket.connect(new InetSocketAddress(config.getHost(), config.getPort()));
    }

    receiver = createReceiver();
    receiver.start();

    if (config.useHeartbeat()) {
      watchdog = createWatchdog();
      watchdog.start();
    }
  }

  private Thread createReceiver() {
    return new Thread() {
      @Override
      public void run() {
        BufferedReader input = null;
        try {
          input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
          while (!isInterrupted()) {
            String command = input.readLine();
            registerReceiving();
            if (command != null && command.length() > 0) {

              // handle header
              Map<String, String> headers = new HashMap<String, String>();
              String line;
              while ((line = input.readLine()) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                  headers.put(line.substring(0, idx).toLowerCase(), line.substring(idx + 1));
                }
              }

              // handle body
              StringBuilder body = new StringBuilder();
              int b;
              while ((b = input.read()) != -1 && b != 0) {
                body.append((char) b);
              }

              if (LOG.isLoggable(Level.INFO)) {
                LOG.info("frame received: command=" + command + ", header=" + headers + ", body=" + body);
              }

              dispatch(command, headers, body.toString());

              if (ERROR.equals(command)) {
                // server closes connection after sending error, so exit after callback
                break;
              }

            }
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          try {
            socket.close();

            if (input != null) {
              input.close();
            }
          } catch (IOException e) {
            // ignore
          }
          isConnected = false;
        }
      }
    };
  }

  private Thread createWatchdog() {
    return new Thread() {
      @Override
      public void run() {
        while (!isInterrupted()) {
          try {
            Thread.sleep(heartbeatMs);
          } catch (InterruptedException e) {
            interrupt();
            // todo. handle interrupt!!!
          }

          long diff = System.currentTimeMillis() - lastReceived;
          if (diff > 2 * heartbeatMs) {
            // consider connection as dead
            stopThread(receiver);
            dispatch(DISCONNECTED, null, null);
            break;
          }

        }
      }
    };
  }

  private static void stopThread(Thread thread) {
    if (thread != null && thread.isAlive()) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  private void registerReceiving() {
    lastReceived = System.currentTimeMillis();
    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("received");
    }
  }

  private void dispatch(String command, Map<String, String> headers, String body) {
    try {
      if (command.equalsIgnoreCase(CONNECTED)) {
        isConnected = true;
        onConnect(headers);
      } else if (command.equalsIgnoreCase(MESSAGE)) {
        onMessage(headers.get("message-id"), null, headers.get("destination"), headers, body);
      } else if (command.equalsIgnoreCase(RECEIPT)) {
        onReceipt(headers.get("receipt-id"));
      } else if (command.equalsIgnoreCase(ERROR)) {
        onError(headers);
      } else if (command.equalsIgnoreCase(DISCONNECTED)) {
        isConnected = false;
        if (LOG.isLoggable(Level.INFO)) {
          LOG.info("disconnected");
        }
        onDisconnect();
      }
    } catch (Exception e) {
      // ignore to not let exceptions produced in callbacks exit the receiver loop
    }
  }


  private void sendFrame(String command, Map<String, String> header, String message) {
    StringBuilder frame = new StringBuilder();
    frame.append(command).append("\n");

    if (header != null) {
      for (Map.Entry<String, String> entry : header.entrySet()) {
        frame.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
      }
    }
    frame.append("\n");

    if (message != null) {
      frame.append(message);
    }

    frame.append("\000");

    try {
      byte[] bytes = frame.toString().getBytes("UTF-8");
      if (socket == null || socket.isClosed()) {
        dispatch(DISCONNECTED, null, null);
        return;
      }
      OutputStream outputStream = socket.getOutputStream();
      outputStream.write(bytes);
      outputStream.write(0);
      outputStream.flush();
      if (LOG.isLoggable(Level.INFO)) {
        LOG.info("frame sent: command=" + command + ", header=" + header + ", body=" + message);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void close() throws IOException {
    if (isConnected) {
      sendFrame(DISCONNECT, null, null);
    }

    stopThread(watchdog);
    stopThread(receiver);

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("closed");
    }
  }

  public void connect() throws IOException {
    startReceiving();

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("started");
    }

    Map<String, String> header = new HashMap<String, String>();
    if (config.getLogin() != null) {
      header.put("login", config.getLogin());
    }
    if (config.getPassword() != null) {
      header.put("passcode", config.getPassword());
    }
    if (config.getVirtualHost() != null) {
      header.put("host", config.getVirtualHost());
    }
    if (config.useHeartbeat()) {
      header.put("heart-beat", "0," + heartbeatMs);
    }
    header.put("accept-version", "1.2");
    sendFrame(CONNECT, header, null);
  }

  public void send(String destination, String message) throws Exception {
    send(destination, message, null, null);
  }

  public void send(String destination, String message, String transaction, Map<String, String> headers) throws Exception {
    if (headers == null) {
      headers = new HashMap<String, String>();
    }
    headers.put("destination", destination);
    if (transaction != null) {
      headers.put("transaction", transaction);
    }
    sendFrame(SEND, headers, message);
  }

  public void subscribe(String destination) throws Exception {
    subscribe(destination, null, null);
  }

  public void subscribe(String destination, String ack) throws Exception {
    subscribe(destination, ack, new HashMap<String, String>());
  }

  public void subscribe(String destination, String ack, Map<String, String> headers) throws Exception {
    if (headers == null) {
      headers = new HashMap<String, String>();
    }
    headers.put("destination", destination);
    if (ack != null) {
      headers.put("ack", ack);
    }
    sendFrame(SUBSCRIBE, headers, null);
  }

  public void unsubscribe(String destination) throws Exception {
    unsubscribe(destination, null);
  }

  public void unsubscribe(String destination, Map<String, String> headers) throws Exception {
    if (headers == null) {
      headers = new HashMap<String, String>();
    }
    headers.put("destination", destination);
    sendFrame(UNSUBSCRIBE, headers, null);
  }

  public void begin(String transaction) throws Exception {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(BEGIN, headers, null);
  }

  public void abort(String transaction) throws Exception {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(ABORT, headers, null);
  }

  public void commit(String transaction) throws Exception {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(COMMIT, headers, null);
  }

  public void ack(String messageId) throws Exception {
    ack(messageId, null);
  }

  public void ack(String messageId, String transaction) throws Exception {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("message-id", messageId);
    if (transaction != null)
      headers.put("transaction", transaction);
    sendFrame(ACK, headers, null);
  }

  /**
   * Utility method to build header map from given strings.
   *
   * @param args Key and value string pairs (first key, second value). Obviously the numbers of args must be even.
   * @return The header map.
   */
  public static Map<String, String> createHeader(String... args) {
    Map<String, String> headers = new HashMap<String, String>(args.length / 2);
    for (int i = 0; i < args.length - 1; i += 2) {
      headers.put(args[i], args[i + 1]);
    }
    return headers;
  }

  /* Methods to be implemented by sublasses.
  Note: These callbacks are not asyncronous, performing blocking operations
  within will also block the receive loop! */

  protected abstract void onConnect(Map<String, String> headers);

  protected abstract void onReceipt(String Id);

  protected abstract void onMessage(String id, String subscription, String destination, Map<String, String> headers, String body);

  protected abstract void onError(Map<String, String> headers);

  protected abstract void onDisconnect();

}
