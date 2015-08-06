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

import java.util.Map;


/**
 * Exception wrapping a STOMP error frame.
 */
public class StompError extends RuntimeException {
  private String body;
  private Map<String, String> headers;

  public String getBody() {
    return body;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public StompError(String body, Map<String, String> headers) {
    this.body = body;
    this.headers = headers;
  }

  @Override
  public String toString() {
    return super.toString() + ": " +
        "body='" + body + '\'' +
        ", headers=" + headers;
  }
}
