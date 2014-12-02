package net.jstomplite;

import net.jstomplite.Frame;

public interface EventListener {


  /**
   * Called when an connction to the stomp server is established.
   */
  void onConnect();


  void onReceipt(String receipt);

  /**
   * Called when a message frame was received.
   *
   */
  void onMessage(Frame frame);

  /**
   * Called when a error frame was received.
   * Note: The stomp client is not closed after.
   */
  void onError(Frame frame);

  /**
   * Called when connection was closed. All client resources are closed too.
   * To reconnect call open(), calling close() before is not necessary.
   */
  void onDisconnect();
}
