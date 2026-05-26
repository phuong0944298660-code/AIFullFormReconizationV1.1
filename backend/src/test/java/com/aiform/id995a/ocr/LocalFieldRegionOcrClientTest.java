package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalFieldRegionOcrClientTest {

  @Test
  void retriesOcrModelAfterHttpFailure() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ocr/crop-recognize-batch", exchange -> respond(exchange, calls.incrementAndGet()));
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
      LocalFieldRegionOcrClient client = new LocalFieldRegionOcrClient(
          new FieldOcrProperties(true, baseUrl, 2),
          new ObjectMapper()
      );

      List<FieldRegionOcrResult> first = client.recognizeBatch(List.of(new byte[] {1, 2, 3}));
      List<FieldRegionOcrResult> second = client.recognizeBatch(List.of(new byte[] {4, 5, 6}));

      assertThat(first).singleElement().extracting(FieldRegionOcrResult::status).isEqualTo("http_502");
      assertThat(second).singleElement().satisfies(result -> {
        assertThat(result.status()).isEqualTo("available");
        assertThat(result.text()).isEqualTo("OK");
        assertThat(result.confidence()).isEqualTo(90);
      });
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int call) throws IOException {
    if (call == 1) {
      write(exchange, 502, "{\"detail\":\"temporary\"}");
      return;
    }
    write(exchange, 200, "{\"results\":[{\"text\":\"OK\",\"confidence\":0.9,\"status\":\"available\"}]}");
  }

  private static void write(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
