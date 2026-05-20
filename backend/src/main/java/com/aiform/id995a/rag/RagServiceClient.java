package com.aiform.id995a.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RagServiceClient {

  private final RagServiceProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  @Autowired
  public RagServiceClient(RagServiceProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newHttpClient());
  }

  RagServiceClient(RagServiceProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  public RetrievalResult retrieve(String query) {
    if (!properties.enabled()) {
      return unavailable("RAG service disabled. Enable rag.enabled when Python rag-service is running.");
    }

    try {
      Map<String, Object> requestBody = Map.of(
          "query", query == null ? "" : query,
          "top_k", Math.max(1, properties.topK()),
          "filters", Map.of("form", "ID995A", "scenario", "student-entry")
      );

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + "/rag/retrieve"))
          .version(HttpClient.Version.HTTP_1_1)
          .timeout(Duration.ofSeconds(Math.max(5, properties.timeoutSeconds())))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return unavailable("RAG service HTTP " + response.statusCode() + ": " + trimForMessage(response.body()));
      }
      return objectMapper.readValue(response.body(), RetrievalResult.class);
    } catch (Exception exception) {
      return unavailable("RAG service unavailable: " + exception.getMessage());
    }
  }

  private RetrievalResult unavailable(String message) {
    return new RetrievalResult(
        List.of(),
        new RetrievalStatus(
            "unavailable",
            false,
            false,
            "",
            0,
            List.of(message)
        )
    );
  }

  private String trimTrailingSlash(String value) {
    String baseUrl = value == null || value.isBlank() ? "http://127.0.0.1:8090" : value;
    return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  private String trimForMessage(String value) {
    if (value == null || value.isBlank()) {
      return "empty response body";
    }
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
  }
}
