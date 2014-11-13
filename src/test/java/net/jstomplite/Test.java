package net.jstomplite;

import java.util.Map;

public class Test {

  public static void main(String[] args) throws Exception {
    StompClient sc = new StompClient(new Config("dev10.secupay-ag.de", 61614, null, "guest", "guest", 0, true, 10)) {
      @Override
      protected void onConnect(final Map<String, String> headers) {
        System.out.println("connected");
        System.out.println();

        new Thread() {
          public void run() {
            try {
//                            subscribe("/queue/test");
              for (int i = 0; i < 1; i++) {
                send("/exchange/connect.api/api:get:General.Skeletons",
//                                send("/exchange/connect.api/api:exec:Smart.Devices.register",
//                                        "{pid: 'me',data:{type:'cashier',uid:'nicotest'}}", null,
                    null, null,
                    createHeader(
                        "content-type", "application/json",
                        "user-id", "guest",
                        "reply-to", "/temp-queue/main",
                        "correlation-id", "" + i + 100,
                        "persistent", "true",
                        "receipt", ""   + i + 100
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
      protected void onDisconnect(Exception ex) {
        System.out.println("disconnect: " + (ex == null ? "" : ex.getMessage()));
      }
    };
    sc.connect(null, null);
    Thread.sleep(10000);
    sc.close(true);
  }
}


