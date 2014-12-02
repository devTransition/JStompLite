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
  private volatile int connected;
  private final Object monitor = new Object();
  private BufferedReader reader;
  private volatile boolean stopReceiver;
  private volatile boolean shutdown;
  private final Map<String, Frame> receipts = new HashMap<>(20);
  private final String id;

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
  public StompClient(String id, Config config) {
    this(id, config, null);
  }

  /**
   * Create a instance with the provided event listener attached.
   */
  public StompClient(String id, Config config, StompEventListener eventListener) {
    this.config = config;
    this.id = id;
    this.eventListener = eventListener == null ? this : eventListener;

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
    if (connected > 0) {
      return;
    }

    connected = 0;

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

    connected = 1;

    stopReceiver = false;
    shutdown = false;
    receiver = new Thread(new Runnable() {
      @Override
      public void run() {
        receive();
      }
    });
    receiver.start();

    sendConnect(login, password); // errors on send will be handled by the receiver thread already

    if (!awaitConnected()) {
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
    if (connected == 0) {
      return;
    }

    shutdown = true;

    String id = createReceiptId();
    try {
      sendDisconnect(id);
    } catch (IOException e) {
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
   * actually only in case of IOException.
   *
   * @param destination
   * @param body
   * @param headers
   * @param requestReceipt  If true a receipt is requested else not.
   * @throws IOException        If a rather technical error happened.
   * @throws StompException     If the server could not process the message correctly and sent an error instead receipt.
   * @throws NoReceiptException If the receipt was not received in time.
   *                            Timeout is {@link Config#getReceiptTimeoutSec()}
   */
  public synchronized void send(String destination, String body, Map<String, String> headers, boolean requestReceipt)
      throws IOException, StompException, NoReceiptException {
    if (connected == 0) {
      return;
    }

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
      return;
    }

    Frame receipt = awaitReceipt(id);
    if (receipt == null) {
      throw new NoReceiptException("No receipt received");
    }

    if (receipt.command.equals(RECEIPT)) {
      // success
      return;
    }

    // this was an error
    throw new StompException(receipt.body, receipt.headers);
  }

  public synchronized void send(String destination, String body, Map<String, String> headers)
      throws IOException, StompException, NoReceiptException {
    send(destination, body, headers, true);
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

  private String createReceiptId() {
    return "rcpt-"  +(id == null ? Integer.toString(hashCode()) : id) + "-" + System.currentTimeMillis();
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

    connected = 0;

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
            Frame frame = readFrame(line, reader);
            if (LOG.isLoggable(Level.FINE)) {
              LOG.fine("frame received: " + frame);
            }
            handleMessage(frame);
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
          handleMessage(new Frame(ERROR, null, out.toString()));
        }
        close(false);
        break;
      }
    }
    LOG.fine("receiver stopped");
  }

  private Frame readFrame(String command, BufferedReader reader) throws IOException {
    Frame frm = new Frame(command);

    // read header
    frm.headers = new HashMap<>(10);
    int contentLength = 0;
    String line;
    while ((line = reader.readLine()).length() > 0) {
      int idx = line.indexOf(':');
      if (idx > 0) {
        String key = line.substring(0, idx).toLowerCase();
        if (!frm.headers.containsKey(key)) {
          // according to the stomp spec just the first header is used if repeated
          String value = line.substring(idx + 1);
          frm.headers.put(key, value);
          if (key.equals("content-length")) {
            contentLength = Integer.parseInt(value);
          }
        }
      }
    }

    // read body
    if (contentLength > 0) {
      char[] buf = new char[contentLength];
      reader.read(buf, 0, contentLength);
      frm.body = new String(buf).trim();
    } else {
      StringBuilder sb = new StringBuilder();
      int b;
      while ((b = reader.read()) != -1 && b != 0) {
        sb.append((char) b);
      }
      frm.body = sb.toString();
    }

    return frm;
  }

  private void handleMessage(Frame frame) {
    try {
      String receiptId = frame.headers == null ? null : frame.headers.get("receipt-id");
      switch (frame.command) {
        case CONNECTED:
          synchronized (monitor) {
            connected = 2;
            monitor.notify();
          }
          if (LOG.isLoggable(Level.INFO)) {
            LOG.info("stomp client connected");
          }
          eventListener.onConnect();
          break;
        case RECEIPT:
          synchronized (receipts) {
            receipts.put(receiptId, new Frame(RECEIPT));
            receipts.notify();
          }
          break;
        case MESSAGE:
          eventListener.onMessage(frame.headers, frame.body);
          break;
        case ERROR:
          if (config.disconnectOnError()) {
            close(true);
          }
          if (receiptId != null) {
            synchronized (receipts) {
              receipts.put(receiptId, frame);
              receipts.notify();
            }
          } else {
            eventListener.onError(frame.headers, frame.body);
          }
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

  private boolean awaitConnected() {
    long maxWaitTime = System.currentTimeMillis() + config.getConnectionTimeoutSec() * 1000;
    synchronized (monitor) {
      while (System.currentTimeMillis() <= maxWaitTime) {
        if (connected == 2) {
          return true;
        }
        try {
          monitor.wait(1000);
        } catch (InterruptedException e) {
          return connected == 2;
        }
      }
    }
    return false;
  }

  private Frame awaitReceipt(String id) {
    long maxWaitTime = System.currentTimeMillis() + config.getReceiptTimeoutSec() * 1000;
    synchronized (receipts) {
      while (System.currentTimeMillis() <= maxWaitTime) {
        Frame receipt = receipts.remove(id);
        if (receipt != null) {
          return receipt;
        }
        try {
          receipts.wait(1000);
        } catch (InterruptedException e) {
          return receipts.remove(id);
        }
      }
    }
    return null;
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


  private class Frame {
    public String command;
    public Map<String, String> headers;
    public String body;

    private Frame() {
    }

    private Frame(String command) {
      this.command = command;
    }

    private Frame(String command, Map<String, String> headers, String body) {
      this.command = command;
      this.headers = headers;
      this.body = body;
    }

    @Override
    public String toString() {
      return "Frame{" +
          "command='" + command + '\'' +
          ", headers=" + headers +
          ", body='" + body + '\'' +
          '}';
    }
  }

}
