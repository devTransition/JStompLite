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

public class Test {
  public static void main(String[] args) throws Exception {
    StompClient.Config config = new StompClient.Config("localhost", 61613, null, "guest", "guest", 0, false, 20, 30, 10);
    final StompClient client = new StompClient("33", config, new StompClient.Listener() {
      @Override
      public void onMessage(Frame frame) {
        System.out.println(frame);
      }

      @Override
      public void onDisconnect() {
        System.out.println("off");
      }
    });
    simple(client);
  }

  private static void simple(StompClient client) {
    try {
      client.connect(null, null);
      client.send("/queue/test", null, null, null);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      client.disconnect();
    }
  }

}


