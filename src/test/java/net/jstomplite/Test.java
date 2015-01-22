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

import java.util.HashMap;
import java.util.Map;

public class Test {
  public static void main(String[] args) throws Exception {
    final StompSupport client = new StompSupport(
        "99", new Config("dev10.secupay-ag.de", 61614, null, "smart", "smart", 0, true, 20, 10, 20), new EventListener() {
      @Override
      public void onConnect() {
        System.out.println();
      }

      @Override
      public void onReceipt(String receipt) {
        System.out.println();
      }

      @Override
      public void onMessage(Frame frame) {
        System.out.println(frame);
      }

      @Override
      public void onError(Frame frame) {
        System.out.println(frame);
      }

      @Override
      public void onDisconnect() {
        System.out.println();
      }
    });
    simple(client);
  }

  private static void simple(StompSupport client) {
    try {
      client.open(null, null);
      Thread.sleep(1000);
      Map<String, String> headers = new HashMap<>();
      headers.put("app-id", "app_a1621caf12f1499c7ffab0c4");
      client.send("/exchange/connect.api/api:get:General.Skeletons", null, headers, true);
      Thread.sleep(10000);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      client.close();
    }
  }

}


