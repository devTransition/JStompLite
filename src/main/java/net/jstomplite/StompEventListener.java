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

import java.util.Map;

/**
 * Interface to implement when acting as listener to events created by the stomp client.
 * Note: These callbacks are not asyncronous called, avoid performing blocking operations within, because it
 * will also block the receive loop of the client!
 */
public interface StompEventListener {


  /**
   * Called when an connction to the stomp server is established.
   *
   */
  void onConnect();

  /**
   * Called when a message frame was received.
   *  @param headers      All the header data at once.
   * @param body         The message body.
   */
  void onMessage(Map<String, String> headers, String body);

  /**
   * Called when a error frame was received.
   * Note: The stomp client is not closed after.
   *
   * @param headers The header data.
   * @param body
   */
  void onError(Map<String, String> headers, String body);

  /**
   * Called when connection was closed. All client resources are closed too.
   * To reconnect call open(), calling close() before is not necessary.
   *
   */
  void onDisconnect();
}
