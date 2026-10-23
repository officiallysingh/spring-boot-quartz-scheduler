/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.quartz.spring.boot.actuate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal JSON-over-HTTP client for the actuator ITs (JDK {@link HttpClient}, no extra dependency).
 */
public final class ActuatorHttp {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final String baseUrl;

  public ActuatorHttp(int port) {
    this.baseUrl = "http://localhost:" + port + "/actuator";
  }

  public record Response(int status, JsonNode body) {}

  public Response get(String path) {
    return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
  }

  public Response post(String path, String json) {
    return send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
  }

  private Response send(HttpRequest.Builder request) {
    try {
      HttpResponse<String> response =
          client.send(
              request.timeout(Duration.ofSeconds(30)).build(),
              HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      return new Response(
          response.statusCode(), (body == null || body.isBlank()) ? null : JSON.readTree(body));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }
}
