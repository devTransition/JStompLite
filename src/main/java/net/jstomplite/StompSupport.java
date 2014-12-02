package net.jstomplite;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal stomp messaging support
 */
public class StompSupport {
  private Socket socket;
  private Thread receiver;
  private final EventListener eventListener;
  private BufferedReader reader;
  private volatile boolean stopReceiver;
  private volatile boolean shutdown;
  private final String id;
  protected final Config config;

  public static final int DEFAULT_SOCKET_TIMEOUT_S = 30;
  public static final String CONNECT = "CONNECT";
  public static final String DISCONNECT = "DISCONNECT";
  public static final String DISCONNECTED = "DISCONNECTED";
  public static final String CONNECTED = "CONNECTED";
  public static final String RECEIPT = "RECEIPT";
  public static final String MESSAGE = "MESSAGE";
  public static final String ERROR = "ERROR";
  public static final String SEND = "SEND";

  private static final List<String> SERVER_FRAMES = Arrays.asList(CONNECTED, RECEIPT, MESSAGE, ERROR);

  private final static Logger LOG = Logger.getLogger(StompSupport.class.getName());

  public StompSupport(String id, Config config, EventListener eventListener) {
    this.eventListener = eventListener;
    this.id = id;
    this.config = config;
  }

  public synchronized void open(String login, String password) throws IOException {
    initConnection();
    sendConnect(login, password);
    // errors on send will be handled by the receiver thread already
  }

  public synchronized String send(String destination, String body, Map<String, String> headers, boolean requestReceipt)
      throws IOException {
    if (headers == null) {
      headers = new HashMap<>();
    }
    String id = createReceiptId();
    headers.put("destination", destination);
    if (requestReceipt) {
      headers.put("receipt", id);
    }
    sendFrame(SEND, headers, body);

    if (!requestReceipt) {
      return null;
    }
    return id;
  }

  public synchronized String close() {
    shutdown = true;
    String id = createReceiptId();
    try {
      sendDisconnect(id);
    } catch (IOException e) {
      // ignore
    }

    return id;

    // closing of all further resources will be done by the receiver thread which
    // gets an error on disconnect
  }

  /**
   * Utility method to build header map from given strings.
   *
   * @param args Key and value string pairs (first key, second value). Obviously the numbers of args must be even.
   * @return The header map.
   */
  public static Map<String, String> createHeader(String... args) {
    Map<String, String> headers = new HashMap<>(args.length / 2);
    for (int i = 0; i < args.length - 1; i += 2) {
      headers.put(args[i], args[i + 1]);
    }
    return headers;
  }


  private void sendDisconnect(String id) throws IOException {
    sendFrame(DISCONNECT, createHeader("receipt", id), null);
  }

  private void sendConnect(String login, String password) throws IOException {
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


  private void sendFrame(String command, Map<String, String> header, String body) throws IOException {
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

    byte[] bytes = null;

    try {
      bytes = frame.toString().getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      // will not happen
    }

    write(bytes);

    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("frame sent: command=" + command + ", header=" + header + ", body=" + body);
    }
  }

  private void dispatchFrame(Frame frame) {
    try {
      switch (frame.getCommand()) {
        case CONNECTED:
          eventListener.onConnect();
          break;
        case DISCONNECTED:
          eventListener.onDisconnect();
          break;
        case RECEIPT:
          String receiptId = frame.getHeaders() == null ? null : frame.getHeaders().get("receipt-id");
          eventListener.onReceipt(receiptId);
          break;
        case MESSAGE:
          eventListener.onMessage(frame);
          break;
        case ERROR:
          eventListener.onError(frame);
          break;
      }
    } catch (Exception e) {
      // ignore to not let exceptions produced in callbacks exit the receiver loop
      if (LOG.isLoggable(Level.SEVERE)) {
        LOG.log(Level.SEVERE, "Client error happened", e);
      }
    }
  }

  private void write(byte[] bytes) throws IOException {
    synchronized (socket) {
      OutputStream out = socket.getOutputStream();
      if (bytes == null) {
        out.write(0);
      } else {
        out.write(bytes);
      }
      out.flush();
    }
  }

  private void initConnection() throws IOException {
    shutdown = true;

    closeConnection(true);

    SocketFactory socketFactory = config.useSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
    socket = socketFactory.createSocket(config.getHost(), config.getPort());

    int timeout = config.getSocketTimeoutSec();
    if (timeout <= 0) {
      timeout = DEFAULT_SOCKET_TIMEOUT_S; // we need a timeout, otherwise the receiver thread can block forever
    }
    socket.setSoTimeout(timeout * 1000);

    if (!socket.isConnected()) {
      socket.connect(new InetSocketAddress(config.getHost(), config.getPort()));
    }

    reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

    stopReceiver = false;
    shutdown = false;
    receiver = new Thread(new Runnable() {
      @Override
      public void run() {
        receive();
      }
    });
    receiver.start();
  }

  private void receive() {
    LOG.fine("receiver started");

    while (!stopReceiver) {
      try {
        String line = reader.readLine();
        if (LOG.isLoggable(Level.FINEST)) {
          LOG.finest("line={" + line + "}");
        }
        if (line == null) {
          write(null);
        } else {
          line = line.trim();
          if (SERVER_FRAMES.contains(line)) {
            Frame frame = new Frame(line, reader);
            if (LOG.isLoggable(Level.FINE)) {
              LOG.fine("frame received: " + frame);
            }
            dispatchFrame(frame);
          }
        }
      } catch (SocketTimeoutException e) {
        // just regular timeout, ignore
      } catch (IOException e) {
        if (!shutdown) {
          // expected, normal case on shutdown (disconnect was sent)
          // report only other
          StringWriter out = new StringWriter();
          e.printStackTrace(new PrintWriter(out));
          dispatchFrame(new Frame(ERROR, null, out.toString()));
        }
        closeConnection(false);
        dispatchFrame(new Frame(DISCONNECTED));
        break;
      }
    }
    LOG.fine("receiver stopped");
  }

  private void closeConnection(boolean stopReceiver) {
    if (stopReceiver && receiver != null && receiver.isAlive()) {
      this.stopReceiver = true;
      try {
        receiver.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }

    if (socket != null) {
      try {
        socket.close();
      } catch (IOException e) {
        // ignore
      }
    }
  }

  private String createReceiptId() {
    return "rcpt-" + (id == null ? Integer.toString(hashCode()) : id) + "-" + System.currentTimeMillis();
  }
}
