package net.jstomplite;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public abstract class StompClient {
  private Socket socket;
  private String host;
  private String virtualHost;
  private String login;
  private String pwd;
  private Thread receiver = null;
  private boolean isConnected = false;
  private boolean useHeartbeat = false;
  public static final int RECEIVE_TIMEOUT = 10000;

  public static final String CONNECT = "CONNECT";
  public static final String DISCONNECT = "DISCONNECT";
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

  public StompClient(String host, int port, String virtualHost, String login, String pwd, boolean useHeartbeat,
                     boolean useSsl) throws IOException {
    this.host = host;
    this.login = login;
    this.pwd = pwd;
    this.virtualHost = virtualHost;
    this.useHeartbeat = useHeartbeat;

    SocketFactory socketFactory = useSsl ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
    socket = socketFactory.createSocket(host, port);

//    socket.setSoTimeout(RECEIVE_TIMEOUT);
    receiver = new Receiver();
    receiver.start();

    // todo: introduce client reusing after close?
  }


  private class Receiver extends Thread {
    @Override
    public void run() {
      BufferedReader input = null;
      try {
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        while (!isInterrupted()) {
          String command = input.readLine();
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

            // handle error
            if (ERROR.equals(command)) {
              // server closes connection after sending error, so exit after callback
              onError(headers);
              break;
            }

            // handle body
            StringBuilder body = new StringBuilder();
            int b;
            while ((b = input.read()) != -1 && b != 0) {
              body.append((char) b);
            }

            dispatch(command, headers, body.toString());
          }
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        try {
          if (socket != null) {
            socket.close();
          }

          if (input != null) {
            input.close();
          }
        } catch (IOException e) {
          // ignore
        }
        isConnected = false;
        socket = null;
      }
    }
  }

  private void dispatch(String command, Map<String, String> headers, String body) {
    if (command.equalsIgnoreCase(CONNECTED)) {
      isConnected = true;
      onConnect(headers);
    } else if (command.equalsIgnoreCase(MESSAGE)) {
      onMessage(headers.get("message-id"), null, headers.get("destination"), headers, body);
    } else if (command.equalsIgnoreCase(RECEIPT)) {
      onReceipt(headers.get("receipt-id"));
    } else {
      throw new RuntimeException("Unknown command " + command);
    }
  }

  private void sendFrame(String command, Map<String, String> header, String message) {
    if (socket == null) {
      throw new RuntimeException("Connection closed");
    }

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
      OutputStream outputStream = socket.getOutputStream();
      outputStream.write(bytes);
      outputStream.write(0);
      outputStream.flush();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void close() throws IOException {
    if (isConnected) {
      sendFrame(DISCONNECT, null, null);
    }
    receiver.interrupt();
    receiver = null;
  }

  public void connect() throws IOException {
    Map<String, String> header = new HashMap<String, String>();
    if (login != null) {
      header.put("login", login);
    }
    if (pwd != null) {
      header.put("passcode", pwd);
    }
    if (virtualHost != null) {
      header.put("host", virtualHost);
    }
    if (useHeartbeat) {
      // todo
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

  abstract void onConnect(Map<String, String> headers);

  abstract void onReceipt(String Id);

  abstract void onMessage(String id, String subscription, String destination, Map<String, String> headers, String body);

  abstract void onError(Map<String, String> headers);

  abstract void onDisconnect();

}
