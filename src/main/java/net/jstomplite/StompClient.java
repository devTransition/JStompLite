/*
 * Copyright (c) 2015. hp.weber GmbH & Co secucard KG (www.secucard.com)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.jstomplite;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal stomp messaging support.
 */
public class StompClient {
  private Socket socket;
  private Thread receiver;
  private final Listener eventListener;
  private BufferedReader reader;
  private volatile boolean stopReceiver;
  private volatile boolean shutdown;
  private volatile boolean initial;
  private volatile boolean connected;
  private volatile Frame error;
  private final String id;
  private final Config config;
  private final Set<String> receipts = new HashSet<>();

  public static final int DEFAULT_SOCKET_TIMEOUT_S = 30;
  public static final String CONNECT = "CONNECT";
  public static final String DISCONNECT = "DISCONNECT";
  public static final String DISCONNECTED = "DISCONNECTED"; // not a real stomp frame just for internal usage
  public static final String CONNECTED = "CONNECTED";
  public static final String RECEIPT = "RECEIPT";
  public static final String MESSAGE = "MESSAGE";
  public static final String ERROR = "ERROR";
  public static final String SEND = "SEND";

  private static final List<String> SERVER_FRAMES = Arrays.asList(CONNECTED, RECEIPT, MESSAGE, ERROR);

  private final static Logger LOG = Logger.getLogger(StompClient.class.getName());

  public StompClient(String id, Config config, Listener eventListener) {
    this.eventListener = eventListener;
    this.id = id;
    this.config = config;

    LOG.info("STOMP client created, " + config);
  }

  /**
   * Connect to STOMP server.
   * Blocks until success or failure when used without callback.
   * In all failure cases all resources are properly closed, no need to call disconnect().
   *
   * @param user     User name.
   * @param password User password.
   * @throws StompError                               if the server responds with an ERROR frame, the frame details are set.
   * @throws NetworkError if the network failed.
   * @throws ClientError  if an general error happened.
   */
  public synchronized void connect(String user, String password) {
    if (connected) {
      return;
    }

    try {
      initConnection();
      sendConnect(user, password);
    } catch (Throwable e) {
      closeConnection(true);
      if (e instanceof IOException) {
        throw new NetworkError(e);
      }
      throw new ClientError(e);
    }

    awaitConnect();
  }

  /**
   * Disconnect connection to STOMP server.
   * The event listener {@link StompClient.Listener#onDisconnect()} gets called after disconnect.
   *
   * @throws NoReceiptException Only if {@link StompClient.Config#requestDISCONNECTReceipt} is true
   *                            (default: false) and no receipt could be received in time.
   * @throws StompError         If an error happens during disconnect attempt. Get details by
   *                            inspecting the properties.
   */
  public synchronized void disconnect() {
    doDisconnect();
  }

  private void doDisconnect() {
    if (!connected) {
      return;
    }
    shutdown = true;
    connected = false;

    if (initial) {
      // just initial state, no connect performed yet, just close all resources
      closeConnection(true);
      return;
    }

    // if connect was performed successfully before must send disconnect
    // closing of all further resources will be done by the receiver thread which gets an error on disconnect

    final String id = config.requestDISCONNECTReceipt ? createReceiptId("disconnect") : null;
    try {
      sendDisconnect(id);
    } catch (IOException e) {
      // ignore an just return
      return;
    }


    if (config.requestDISCONNECTReceipt) {
      awaitReceipt(id, false, null);  // no disconnect because we already sent it
    }

    dispatchFrame(new Frame(DISCONNECTED));
  }


  /**
   * Send a message to the stomp server.
   * Blocks until a receipt is received or receipt timeout if no callback is provided.
   * The connection is automatically closed when no receipt could be received in time if
   * {@link Config#disconnectOnSENDReceiptTimeout} is true (default).
   *
   * @param destination The destination string.
   * @param body        The message body or null.
   * @param headers     The message headers or null.
   * @param timeoutSec  Timeout for awaiting receipt. Pass null to use the config value.
   * @throws StompError                               If an error happens during sending.
   *                                                  Get details by inspecting the properties.
   *                                                  All resources are closed properly, no need to call disconnect().
   * @throws NoReceiptException                       If no receipt is received after
   *                                                  {@link Config#receiptTimeoutSec}. Will NEVER be
   *                                                  thrown when a callback is provided.
   * @throws NetworkError if the networks failed.
   */
  public synchronized void send(String destination, String body, Map<String, String> headers, Integer timeoutSec) {
    if (headers == null) {
      headers = new HashMap<>();
    }
    String id = createReceiptId(body);
    headers.put("destination", destination);
    if (config.requestSENDReceipt) {
      headers.put("receipt", id);
    }

    try {
      sendFrame(SEND, headers, body);
    } catch (Throwable t) {
      closeConnection(true);
      if (t instanceof IOException) {
        throw new NetworkError(t);
      }
      throw new ClientError(t);
    }

    awaitReceipt(id, config.disconnectOnSENDReceiptTimeout, timeoutSec);
  }

  public boolean isConnected() {
    return connected;
  }

  private void onConnected() {
    connected = true;
    initial = false;
  }

  private void onDisconnected() {
    connected = false;
    initial = false;
    eventListener.onDisconnect();
  }

  private void onReceipt(Frame frame) {
    String receiptId = frame.getHeaders() == null ? null : frame.getHeaders().get("receipt-id");
    if (receiptId != null) {
      synchronized (receipts) {
        receipts.add(receiptId);
      }
    }
  }

  private void onMessage(Frame frame) {
    eventListener.onMessage(frame);
  }

  private void onError(Frame frame) {
    // always treat as response to a message
    // just set as current error, must be handled when waiting for connection or receipt
    error = frame;
  }


  private void awaitConnect() {
    final long maxWaitTime = System.currentTimeMillis() + config.connectionTimeoutSec * 1000;
    while (System.currentTimeMillis() <= maxWaitTime) {
      if (connected || error != null) {
        break;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        // will be stopped anyway
      }
    }

    if (connected) {
      return;
    }

    if (error != null) {
      closeConnection(true);
      String body = error.getBody();
      Map<String, String> headers = error.getHeaders();
      error = null;
      throw new StompError(body, headers);
    } else {
      closeConnection(true);
      throw new NetworkError("Timout waiting for connection.");
    }
  }

  private void awaitReceipt(final String receiptId, final boolean disconnect, Integer timeoutSec) {
    // check if receipt was received
    if (timeoutSec == null) {
      timeoutSec = config.receiptTimeoutSec;
    }
    boolean found = false;
    long maxWaitTime = System.currentTimeMillis() + timeoutSec * 1000;
    outer:
    while (System.currentTimeMillis() <= maxWaitTime && connected && error == null) {
      synchronized (receipts) {
        Iterator<String> it = receipts.iterator();
        while (it.hasNext()) {
          String next = it.next();
          if (next.equals(receiptId)) {
            it.remove();
            found = true;
            break outer;
          }
        }
      }

//      LOG.trace("waiting for receipt: ", receiptId);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        // will be stopped anyway
      }
    }

    // we can treat error as reason to disconnect

    if (error != null || !found) {
      if (connected && disconnect) {
        try {
          disconnect();
        } catch (Throwable t) {
          // ignore all
          LOG.log(Level.SEVERE, "Error disconnecting due receipt timeout or error.", t);
        }
      }
      if (error != null) {
        String body = error.getBody();
        Map<String, String> headers = error.getHeaders();
        error = null;
        throw new StompError(body, headers);
      } else {
        throw new NoReceiptException("No receipt frame received for sent message.");
      }
    }
    // consider receipt as successful disconnect
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


  private void sendDisconnect(String receiptId) throws IOException {
    sendFrame(DISCONNECT, receiptId == null ? null : createHeader("receipt", receiptId), null);
  }

  private void sendConnect(String login, String password) throws IOException {
    Map<String, String> header = new HashMap<>();

    String log = login == null ? config.login : login;
    if (log != null) {
      header.put("login", log);
    }
    String pwd = password == null ? config.password : password;
    if (pwd != null) {
      header.put("passcode", pwd);
    }
    if (config.virtualHost != null) {
      header.put("host", config.virtualHost);
    }
    if (config.heartbeatMs > 0) {
      header.put("heart-beat", config.heartbeatMs + ",0");
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
      LOG.fine("Frame sent: command=" + command + ", header=" + header + ", body=" + body);
    }
  }

  private void dispatchFrame(Frame frame) {
    try {
      switch (frame.getCommand()) {
        case CONNECTED:
          onConnected();
          break;
        case DISCONNECTED:
          onDisconnected();
          break;
        case RECEIPT:
          onReceipt(frame);
          break;
        case MESSAGE:
          onMessage(frame);
          break;
        case ERROR:
          onError(frame);
          break;
      }
    } catch (Exception e) {
      // ignore to not let exceptions produced in callbacks exit the receiver loop
      LOG.log(Level.SEVERE, "Client error happened.", e);
    }
  }

  private void write(byte[] bytes) throws IOException {
    synchronized (socket) {
      if (socket.isClosed()) {
        LOG.info("Trying to write on closed socket: " + new String(bytes));
        return;
      }
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
    initial = false;
    receipts.clear();
    error = null;

    closeConnection(true);

    if (config.useSsl) {
      SSLSocket sslSocket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(config.host, config.port);
      // fix TLSv1.2 not in enabled but in supported protocol list, needed to support it
      sslSocket.setEnabledProtocols(sslSocket.getSupportedProtocols());
      socket = sslSocket;
    } else {
      socket = SocketFactory.getDefault().createSocket(config.host, config.port);
    }

    int timeout = config.socketTimeoutSec;
    if (timeout <= 0) {
      timeout = DEFAULT_SOCKET_TIMEOUT_S; // we need a timeout, otherwise the receiver thread can block forever
    }
    socket.setSoTimeout(timeout * 1000);

    if (!socket.isConnected()) {
      socket.connect(new InetSocketAddress(config.host, config.port));
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
    initial = true;
  }

  private void receive() {
    LOG.info("Receiver started.");

    while (!stopReceiver) {
      try {
        // socket is supposed to have timeout set!
        // that means reading will block until line is read or timeout,
        // this is to avoid endless blocking since it gives time to react on stopReceiver flag
        String line = reader.readLine();
        if (LOG.isLoggable(Level.FINEST)) {
          LOG.finest("line={" + line + "}");
        }
        if (line == null) {
          // indicates connection is dropped
          // write test to see if it's the case - if write throws IOException we must close
          write(null);
        } else {
          line = line.trim();
          if (SERVER_FRAMES.contains(line)) {
            Frame frame = new Frame(line, reader);
            if (LOG.isLoggable(Level.FINE)) {

              LOG.fine("Frame received: " + frame);
            }
            dispatchFrame(frame);
          }
        }
      } catch (SocketTimeoutException e) {
        // just regular configured socket timeout, ignore and go on
      } catch (Exception e) {
        if (LOG.isLoggable(Level.FINEST)) {
          LOG.log(Level.FINEST, "Exception happened: ", e);
        }
        // in most cases this would be an IOException, coming from dropped connection
        // very unlikely that the receiver loop would work after, so quit and close all
        closeConnection(false);
        if (!shutdown) {
          // exception is expected on shutdown (disconnect was sent), report only in other cases
          // just log, client can't do something meaningful usually
          if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Error in receiver loop.", e);
          }
          dispatchFrame(new Frame(DISCONNECTED));
        }
        break;
      }
    }
    LOG.info("Receiver stopped.");
  }

  /**
   * Closing all resources silently, no exception is raised because we can do nothing.
   *
   * @param stopReceiver True if receiver loop should also be stopped, false else.
   */
  private void closeConnection(boolean stopReceiver) {
    connected = false;
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
        LOG.log(Level.SEVERE, "Error closing socket", e);
      }
    }
  }

  private String createReceiptId(String str) {
    return "rcpt-" + id + "-" + str.hashCode() + "-" + System.currentTimeMillis();
  }

  /**
   * Listener interface to get notified when stuff happened.
   * Note: Exceptions thrown by methods are swallowed (just logged) to not break this clients message receiver loop by
   * any exception caused by client code.
   */
  public static interface Listener {

    /**
     * Called when a message frame was received.
     */
    void onMessage(Frame frame);

    /**
     * Called when the connection state has changed to disconnect.
     * Just called when the connection is broken or so, not when disconnecting by intention.
     */
    void onDisconnect();
  }

  public static class Config {
    // send DISCONNECT when a SEND receipt times out
    private final boolean disconnectOnSENDReceiptTimeout = true;

    // request a receipt on SEND
    private final boolean requestSENDReceipt = true;

    // request a receipt on DISCONNECT
    private final boolean requestDISCONNECTReceipt = false;

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
    Note: the the stomp client itself will not deliver heartbeat, this must be done by user sending messages.
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

    @Override
    public String toString() {
      return "Config{" +
          "disconnectOnSENDReceiptTimeout=" + disconnectOnSENDReceiptTimeout +
          ", requestSENDReceipt=" + requestSENDReceipt +
          ", requestDISCONNECTReceipt=" + requestDISCONNECTReceipt +
          ", host='" + host + '\'' +
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
}
