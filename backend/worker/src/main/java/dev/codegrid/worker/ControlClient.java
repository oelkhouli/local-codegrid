package dev.codegrid.worker;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class ControlClient {
  public static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String base, node, token;
  private String ledger = "";

  public void ledger(String id) {
    ledger = id;
  }

  public static class Rejected extends RuntimeException {
    public final int status;

    Rejected(int s) {
      super("Control request rejected: " + s);
      status = s;
    }
  }

  public ControlClient(String base, String node, String token) {
    this.base = base;
    this.node = node;
    this.token = token;
  }

  public JsonNode get(String path) throws Exception {
    return request("GET", path, null);
  }

  public JsonNode post(String path, Object body) throws Exception {
    return request("POST", path, body);
  }

  public JsonNode request(String method, String path, Object body) throws Exception {
    String value = body == null ? "" : JSON.writeValueAsString(body);
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(5))
            .header("X-CodeGrid-Node", node)
            .header("X-CodeGrid-Ledger", ledger)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(value))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var stream = response.body()) {
      byte[] bytes = stream.readNBytes(131073);
      if (bytes.length > 131072) throw new IllegalStateException("Control response too large");
      if (response.statusCode() >= 400) throw new Rejected(response.statusCode());
      return JSON.readTree(bytes);
    }
  }
}
