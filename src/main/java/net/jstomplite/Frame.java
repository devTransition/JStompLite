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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Frame {
  private String command;
  private Map<String, String> headers;
  private String body;

  public Frame() {
  }

  public Frame(String command) {
    this.command = command;
  }

  public Frame(String command, Map<String, String> headers, String body) {
    this.command = command;
    this.headers = headers;
    this.body = body;
  }

  public String getCommand() {
    return command;
  }

  public void setCommand(String command) {
    this.command = command;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public Frame(String command, BufferedReader reader) throws IOException {
    this.command = command;

    // read header
    headers = new HashMap<>(10);
    int contentLength = 0;
    String line;
    while ((line = reader.readLine()).length() > 0) {
      int idx = line.indexOf(':');
      if (idx > 0) {
        String key = line.substring(0, idx).toLowerCase();
        if (!headers.containsKey(key)) {
          // according to the stomp spec just the first header is used if repeated
          String value = line.substring(idx + 1);
          headers.put(key, value);
          if (key.equals("content-length")) {
            contentLength = Integer.parseInt(value);
          }
        }
      }
    }

    // read body
    if (contentLength > 0) {
      char[] buf = new char[contentLength];
      int len = contentLength;
      int offset = 0;
      while (len > 0) {
        int read = reader.read(buf, offset, len);
        offset += read;
        len -= read;
      }
      body = new String(buf).trim();
    } else {
      StringBuilder sb = new StringBuilder();
      int b;
      while ((b = reader.read()) != -1 && b != 0) {
        sb.append((char) b);
      }
      body = sb.toString();
    }
  }

  public Map<String, String> toMap() {
    Map<String, String> map = new HashMap<>();
    if (headers != null) {
      map.putAll(headers);
    }
    if (body != null) {
      map.put("body", body);
    }
    return map;
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
