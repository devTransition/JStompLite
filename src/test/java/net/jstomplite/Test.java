package net.jstomplite;

import java.util.Map;

public class Test {

  public static void main(String[] args) throws Exception {
    StompClient sc = new StompClient(new Config("dev10.secupay-ag.de", 61614, null, "guest", "guest", false, true)) {
      @Override
      protected void onConnect(final Map<String, String> headers) {
        System.out.println("connected");
        System.out.println();

        new Thread() {
          public void run() {
            try {
//                            subscribe("/queue/test");
              for (int i = 0; i < 10; i++) {
                send("/exchange/connect.api/api:get:General.Skeletons",
//                                send("/exchange/connect.api/api:exec:Smart.Devices.register",
//                                        "{pid: 'me',data:{type:'cashier',uid:'nicotest'}}", null,
                    null, null,
                    createHeader(
                        "content-type", "application/json",
                        "user-id", "guest",
                        "reply-to", "/temp-queue/main",
                        "correlation-id", "" + i,
                        "persistent", "true",
                        "receipt", "message" + i
                    )
                );
                Thread.sleep(10000);
              }
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        }.start();
      }

      @Override
      protected void onReceipt(String id) {
        System.out.println("got receipt: " + id);
        System.out.println();
      }

      @Override
      protected void onMessage(String id, String subscription, String destination, Map<String, String> headers, String body) {
        System.out.println("message:");
        System.out.println("body: " + body);
        System.out.println("header: " + headers);
        System.out.println();
      }

      @Override
      protected void onError(Map<String, String> headers) {
        System.out.println("error:");
        System.out.println("header: " + headers);
        System.out.println();
      }

      @Override
      protected void onDisconnect() {

      }
    };
    sc.connect();
    Thread.sleep(10000);
    sc.close();
  }
}


