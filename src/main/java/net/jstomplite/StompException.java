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
 * Exception wrapping a stomp server error.
 */
public class StompException extends Exception {
  private String body;
  private Map headers;

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public Map getHeaders() {
    return headers;
  }

  public void setHeaders(Map headers) {
    this.headers = headers;
  }

  public StompException(String body, Map headers) {
    this.body = body;
    this.headers = headers;
  }

  public StompException(String message, String body, Map headers) {
    super(message);
    this.body = body;
    this.headers = headers;
  }

  public StompException() {
  }

  public StompException(String message) {
    super(message);
  }
}
