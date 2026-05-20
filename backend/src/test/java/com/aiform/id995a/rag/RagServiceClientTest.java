package com.aiform.id995a.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RagServiceClientTest {

  @Test
  void includesResponseBodyWhenRagServiceReturnsNonSuccessStatus() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/rag/retrieve", exchange -> {
      byte[] body = "{\"detail\":\"bad payload\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(422, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    try {
      RagServiceClient client = new RagServiceClient(
          new RagServiceProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), 5, 8),
          new ObjectMapper()
      );

      RetrievalResult result = client.retrieve("missing fields");

      assertThat(result.status().mode()).isEqualTo("unavailable");
      assertThat(result.status().messages()).singleElement()
          .asString()
          .contains("HTTP 422")
          .contains("bad payload");
    } finally {
      server.stop(0);
    }
  }
}
