package net.jstomplite;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
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
  private boolean connected;

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

  public StompClient(final Config config) {
    this.config = config;
  }

  private void startReceiver() throws IOException {
    // make sure everything is closed
    connected = false;
    stopReceiver();
    closeSocket();

    if (socket == null || socket.isClosed()) {
      SocketFactory socketFactory = config.useSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
      socket = socketFactory.createSocket(config.getHost(), config.getPort());
    }

    socket.setSoTimeout(config.getSocketTimeoutSec() * 1000);

    if (!socket.isConnected()) {
      socket.connect(new InetSocketAddress(config.getHost(), config.getPort()));
    }
    connected = true;

    receiver = createReceiver();
    receiver.start();
  }

  private Thread createReceiver() {
    return new Thread() {
      @Override
      public void run() {
        try {
          BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
          while (!isInterrupted()) {
            String command;
            try {
              command = input.readLine();
            } catch (java.net.SocketTimeoutException e) {
              command = null;
            }
            if (command == null) {
              // null shoud never happen under normal circumstances
              // this indicates lost connection or timeout, attempting write will throw exception when actually broken
              write(new byte[]{0});
            } else if (command.length() > 0) {
              // handle header
              Map<String, String> headers = new HashMap<String, String>();
              String line;
              while ((line = input.readLine()) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                  String key = line.substring(0, idx).toLowerCase();
                  if (!headers.containsKey(key)) {
                    // according to the stomp spec just the first header is used if repeated
                    headers.put(key, line.substring(idx + 1));
                  }
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

              dispatch(command, headers, body.toString(), null);
            }
          }
        } catch (Exception e) {
          // abnormal end - inform client
          closeSocket();
          dispatch(DISCONNECTED, null, null, e);
        }
      }
    };
  }


  private void dispatch(String command, Map<String, String> headers, String body, Exception ex) {
    try {
      if (command.equalsIgnoreCase(CONNECTED)) {
        onConnect(headers);
      } else if (command.equalsIgnoreCase(MESSAGE)) {
        onMessage(headers.get("message-id"), null, headers.get("destination"), headers, body);
      } else if (command.equalsIgnoreCase(RECEIPT)) {
        onReceipt(headers.get("receipt-id"));
      } else if (command.equalsIgnoreCase(ERROR)) {
        onError(headers);
        // if server would close connection after error (like the stomp spec said)
        // it would handled by the receiver thread (exception)
        // if not we can go on
      } else if (command.equalsIgnoreCase(DISCONNECTED)) {
        if (LOG.isLoggable(Level.INFO)) {
          LOG.info("closed");
        }
        onDisconnect(ex);
      }
    } catch (Exception e) {
      // ignore to not let exceptions produced in callbacks exit the receiver loop
      if (LOG.isLoggable(Level.SEVERE)) {
        LOG.log(Level.SEVERE, "Client error happened", e);
      }
    }
  }

  private boolean sendFrame(String command, Map<String, String> header, String body) {
    StringBuilder frame = new StringBuilder();
    frame.append(command).append("\n");

    if (header != null) {
      for (Map.Entry<String, String> entry : header.entrySet()) {
        frame.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
      }
    }
    frame.append("\n");

    if (body != null) {
      frame.append(body);
    }

    frame.append("\000");

    byte[] bytes = new byte[0];

    try {
      bytes = frame.toString().getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }

    try {
      if (!write(bytes)) {
        return false;
      }
    } catch (IOException e) {
      // connection failure, inform client
      stopReceiver();
      dispatch(DISCONNECTED, null, null, e);
      return false;
    }

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("frame sent: command=" + command + ", header=" + header + ", body=" + body);
    }

    return true;
  }

  /**
   * Write bytes to the socket.
   * Used sometimes from receiver thread also, synchonize to avoid effects.
   * Additionally avoids writing again to the socket if there was an error before.
   */
  private synchronized boolean write(byte[] bytes) throws IOException {
    if (connected) {
      try {
        OutputStream out = socket.getOutputStream();
        out.write(bytes);
        out.write(0);
        out.flush();
      } catch (IOException e) {
        closeSocket();
        throw e;
      }
      return true;
    }

    return false;
  }

  private void stopReceiver() {
    if (receiver != null && receiver.isAlive()) {
      receiver.interrupt();
      try {
        receiver.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }
  }

  private void closeSocket() {
    connected = false;

    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  public void connect() throws IOException {
    connect(null, null);
  }

  /**
   * Connect to the Stomp server using the given configuration.
   * No need to call close() in error case. Depending on the error user may call again.
   *
   * @param login
   * @param password
   * @throws IOException if a error occurs.
   */
  public void connect(String login, String password) throws IOException {
    startReceiver();

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("started");
    }

    Map<String, String> header = new HashMap<String, String>();

    String log = login == null ? config.getLogin() : login;
    if (log != null) {
      header.put("login", log);
    }
    String pwd = password == null ? config.getPassword() : password;
    if (pwd != null) {
      header.put("passcode", pwd);
    }
    if (config.getVirtualHost() != null) {
      header.put("host", config.getVirtualHost());
    }
    if (config.getHeartbeatMs() > 0) {
      header.put("heart-beat", config.getHeartbeatMs() + ",0");
    }
    header.put("accept-version", "1.2");
    sendFrame(CONNECT, header, null);
  }

  /**
   * Closing the client properly.
   * Will cause call to onDisconnect() only if successful.
   * No effect on a already closed or not connected client.
   */
  public void close() {
    // note: if not sent successfully shutdown will be performed anyway
    if (sendFrame(DISCONNECT, null, null)) {
      // server closes connection on disconnect,
      // receiver thread will probably detect first and closes all, no need to do it again
      // but wait a little for it
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
      }

      if (connected) {
        stopReceiver();
        closeSocket();
        dispatch(DISCONNECTED, null, null, null);
      }
    }
  }

  public void send(String destination, String message) throws Exception {
    send(destination, message, null, null);
  }

  public void send(String destination, String body, Map<String, String> headers) throws Exception {
    send(destination, body, null, headers);
  }

  public void send(String destination, String body, String transaction, Map<String, String> headers) {
    if (headers == null) {
      headers = new HashMap<>();
    }
    headers.put("destination", destination);
    if (transaction != null) {
      headers.put("transaction", transaction);
    }
    sendFrame(SEND, headers, body);
  }

  public void subscribe(String destination) throws Exception {
    subscribe(destination, null, null);
  }

  public void subscribe(String destination, String ack) throws Exception {
    subscribe(destination, ack, new HashMap<String, String>());
  }

  public void subscribe(String destination, String ack, Map<String, String> headers) {
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

  public void unsubscribe(String destination, Map<String, String> headers) {
    if (headers == null) {
      headers = new HashMap<String, String>();
    }
    headers.put("destination", destination);
    sendFrame(UNSUBSCRIBE, headers, null);
  }

  public void begin(String transaction) {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(BEGIN, headers, null);
  }

  public void abort(String transaction) {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(ABORT, headers, null);
  }

  public void commit(String transaction) {
    HashMap<String, String> headers = new HashMap<String, String>();
    headers.put("transaction", transaction);
    sendFrame(COMMIT, headers, null);
  }

  public void ack(String messageId) throws Exception {
    ack(messageId, null);
  }

  public void ack(String messageId, String transaction) {
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

  //--------------------------------------------------------------------------------------------------------------------
  /*
  Methods to be implemented by sublasses.
  Note: These callbacks are not asyncronous, performing blocking operations
  within will also block the receive loop!
  */

  /**
   * Called when a connected frame was received.
   *
   * @param headers The header data.
   */
  protected abstract void onConnect(Map<String, String> headers);

  /**
   * Called when a receipt frame was received.
   *
   * @param Id The receipt-id header.
   */
  protected abstract void onReceipt(String Id);

  /**
   * Called when a message frame was received.
   *
   * @param id           The message-id header.
   * @param subscription The subscription header.
   * @param destination  The destination header.
   * @param headers      All the header data at once.
   * @param body         The message body.
   */
  protected abstract void onMessage(String id, String subscription, String destination, Map<String, String> headers,
                                    String body);

  /**
   * Called when a error frame was received.
   * Note: This client is not closed after.
   *
   * @param headers The header data.
   */
  protected abstract void onError(Map<String, String> headers);

  /**
   * Called when connection was closed. All client resources are closed too.
   * To reconnect call connect(), calling close() before is not necessary.
   *
   * @param ex The exception when closing was caused by an error, null else.
   */
  protected abstract void onDisconnect(Exception ex);

}
