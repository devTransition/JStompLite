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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple stomp client implementation.
 * Supports only client frames CONNECT, DISCONNECT and SEND.
 * Waits for CONNECTED on CONNECT and waits also for RECEIPT/ERROR on SEND and DISCONNECT,
 * so no receipt handling necessary on using side.
 * Add a listener (or use this instance as listener) to get notified when connection is established/closed and messages
 * or errors arrive. Error messages directly related to a sent message (via receipt) will not be propagated to the
 * listener instead they are wrapped in a exception thrown when sending the message.
 */
public class StompClient implements StompEventListener {
  private final Config config;
  private Socket socket;
  private Thread receiver;
  private StompEventListener eventListener;
  private volatile boolean connected;
  private BufferedReader reader;
  private volatile boolean stopReceiver;
  private volatile boolean shutdown;
  private final Map<String, Object> receipts = new ConcurrentHashMap<>(20, 0.75f, 2);

  public static final int DEFAULT_SOCKET_TIMEOUT_S = 30;
  public static final String CONNECT = "CONNECT";
  public static final String DISCONNECT = "DISCONNECT";
  public static final String CONNECTED = "CONNECTED";
  public static final String RECEIPT = "RECEIPT";
  public static final String MESSAGE = "MESSAGE";
  public static final String ERROR = "ERROR";
  public static final String SEND = "SEND";

  private static final List<String> SERVER_FRAMES = Arrays.asList(CONNECTED, RECEIPT, MESSAGE, ERROR);

  private final static Logger LOG = Logger.getLogger(StompClient.class.getName());

  /**
   * Creates a instance with itself attached as event lister.
   * Override the according methods like {@link StompEventListener#onConnect()}
   */
  public StompClient(Config config) {
    this(config, null);
  }

  /**
   * Create a instance with the provided event listener attached.
   */
  public StompClient(Config config, StompEventListener eventListener) {
    this.config = config;
    this.eventListener = eventListener == null ? this : eventListener;
    this.connected = false;

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("stomp client created, " + config);
    }
  }

  /**
   * Connects to the stomp server.
   * Throws exceptions if not sucessfully, this client should be closed then.
   * On success {@link StompEventListener#onConnect()} is called.
   *
   * @param login    User to connect, may be null.
   * @param password password, may be null.
   * @throws IOException                If a error ocurrs.
   * @throws ConnectionTimeoutException If the server does not acknowledge the connection in time,
   *                                    timeout is {@link Config#getConnectionTimeoutSec()}
   */
  public synchronized void open(String login, String password) throws IOException, ConnectionTimeoutException {
    if (connected) {
      return;
    }

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
    connected = false;
    shutdown = false;
    receiver = new Thread(new Runnable() {
      @Override
      public void run() {
        receive();
      }
    });
    receiver.start();

    sendConnect(login, password); // errors on send will be handled by the receiver thread already

    awaitConnected();

    if (!connected) {
      close(true);
      throw new ConnectionTimeoutException();
    }

    try {
      eventListener.onConnect();
    } catch (Exception e) {
      // ignore
    }

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("stomp client connected");
    }
  }

  /**
   * Closing the server connection by sending DISCONNECT frame, waiting for the receipt and closing all resources.
   * {@link net.jstomplite.StompEventListener#onDisconnect()} is called when the connection was really closed.
   * This is not the case when calling close on a already closed client.
   * The methods fails silently, but the client can be considered as closed anyway.
   */
  public synchronized void close() {
    if (!connected) {
      return;
    }

    shutdown = true;

    String id = createReceiptId();
    try {
      sendDisconnect(id);
    } catch (IOException e) {
      e.printStackTrace();
      // ignore
    }

    awaitReceipt(id);

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("stomp client closed");
    }
    // maybe a receipt was never received here at least we have waited a bit
    // to allow server getting all messages

    // closing of all further resources will be done by the receiver thread which gets an error on disconnct
  }

  /**
   * Submitting a SEND frame and waits for the receipt.
   * Throws a exception if not successfully sent. Depending on the exception the client should be closed,
   * actually only in case of IOException. But
   *
   * @param destination
   * @param body
   * @param headers
   * @throws IOException        If a rather technical error happened.
   * @throws StompException     If the server could not process the message correctly and sent an error instead receipt.
   * @throws NoReceiptException If the receipt was not received in time.
   *                            Timeout is {@link Config#getReceiptTimeoutSec()}
   */
  public synchronized void send(String destination, String body, Map<String, String> headers)
      throws IOException, StompException, NoReceiptException {
    if (!connected) {
      return;
    }

    if (headers == null) {
      headers = new HashMap<>();
    }
    String id = createReceiptId();
    headers.put("destination", destination);
    headers.put("receipt", id);
    sendFrame(SEND, headers, body);

    awaitReceipt(id);

    if (!receipts.containsKey(id)) {
      throw new NoReceiptException("No receipts");
    }

    Object value = receipts.remove(id);
    if ("".equals(value)) {
      // success
      return;
    }

    // this is a error
    Map err = (Map) value;
    throw new StompException((String) err.get("body"), (Map) err.get("headers"));
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

    if (LOG.isLoggable(Level.INFO)) {
      LOG.info("frame sent: command=" + command + ", header=" + header + ", body=" + body);
    }
  }

  private String createReceiptId() {
    // todo: user id field instead of hashCode?
    return "" + hashCode() + System.currentTimeMillis();
  }

  private void close(boolean stopReceiver) {
    if (stopReceiver) {
      this.stopReceiver = true;
      try {
        receiver.join();
      } catch (InterruptedException e) {
        // ignore
      }
    }

    connected = false;

    try {
      socket.close();
    } catch (IOException e) {
      // ignore
    }

    try {
      eventListener.onDisconnect();
    } catch (Exception e) {
      // ignore
    }
  }


  private void receive() {
    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("receiver started");
    }

    Map<String, String> headers = new HashMap<>(10);
    String body = null, command = null;
    while (!stopReceiver) {
      try {
        String line = reader.readLine();
        if (line == null) {
          write(null);

        } else if (SERVER_FRAMES.contains(line)) {
          headers.clear();
          command = line;
          body = null;

        } else if (line.equals("\u0000")) {
          if (LOG.isLoggable(Level.INFO)) {
            LOG.info("frame received: command=" + command + ", header=" + headers + ", body=" + body);
          }
          handleMessage(command, new HashMap<>(headers), body); // must copy headers
          headers.clear();
          body = command = null;

        } else if (line.length() == 0 && body == null) {
          // header separator, switch to body
          body = "";

        } else if (body == null) {
          int idx = line.indexOf(':');
          if (idx > 0) {
            String key = line.substring(0, idx).toLowerCase();
            if (!headers.containsKey(key)) {
              // according to the stomp spec just the first header is used if repeated
              headers.put(key, line.substring(idx + 1));
            }
          }

        } else {
          body += line;

        }

      } catch (SocketTimeoutException e) {
        // just regular timeout, ignore
      } catch (IOException e) {
        if (!shutdown) {
          // expected, normal case on shutdown (disconnect was sent)
          // report only other
          StringWriter out = new StringWriter();
          e.printStackTrace(new PrintWriter(out));
          handleMessage(ERROR, null, out.toString());
        }
        close(false);
        break;
      }
    }
    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine("receiver stopped");
    }
  }

  private void handleMessage(String command, Map<String, String> headers, String body) {
    try {
      String receiptId = headers == null ? null : headers.get("receipt-id");
      if (command.equals(CONNECTED)) {
        connected = true;
      } else if (command.equals(RECEIPT)) {
        receipts.put(receiptId, "");
      } else if (command.equals(MESSAGE)) {
        eventListener.onMessage(headers, body);
      } else if (command.equals(ERROR)) {
        if (receiptId != null) {
          Map message = new HashMap();
          message.put("body", body);
          message.put("headers", headers);
          receipts.put(receiptId, message);
        } else {
          eventListener.onError(headers, body);
        }
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

  private void awaitConnected() {
    long maxWaitTime = System.currentTimeMillis() + config.getConnectionTimeoutSec() * 1000;
    while ((System.currentTimeMillis() < maxWaitTime) && !connected) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        break;
      }
    }
  }

  private void awaitReceipt(String id) {
    long maxWaitTime = System.currentTimeMillis() + config.getReceiptTimeoutSec() * 1000;
    while ((System.currentTimeMillis() < maxWaitTime) && !receipts.containsKey(id)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        break;
      }
    }
    // todo: cleanup old receipts
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

  // methods to override to use this instance directly as listener ----------------------------------

  @Override
  public void onConnect() {
  }

  @Override
  public void onMessage(Map<String, String> headers, String body) {
  }

  @Override
  public void onError(Map<String, String> headers, String body) {
  }

  @Override
  public void onDisconnect() {
  }

}
