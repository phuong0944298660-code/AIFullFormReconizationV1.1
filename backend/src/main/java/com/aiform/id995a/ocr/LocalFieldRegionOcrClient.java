package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalFieldRegionOcrClient implements FieldRegionOcrGateway {

  private final FieldOcrProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  @Autowired
  public LocalFieldRegionOcrClient(FieldOcrProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  LocalFieldRegionOcrClient(
      FieldOcrProperties properties,
      ObjectMapper objectMapper,
      HttpClient httpClient
  ) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  @Override
  public List<FieldRegionOcrResult> recognizeBatch(List<byte[]> cropImageBytes) throws IOException {
    if (!properties.enabled()) {
      return unavailableResults(cropImageBytes, "disabled");
    }
    if (cropImageBytes == null || cropImageBytes.isEmpty()) {
      return List.of();
    }

    String boundary = "----field-ocr-" + UUID.randomUUID();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + "/ocr/crop-recognize-batch"))
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, cropImageBytes)))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return unavailableResults(cropImageBytes, "http_" + response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      JsonNode results = root.path("results");
      if (!results.isArray()) {
        return unavailableResults(cropImageBytes, "invalid_response");
      }
      return paddedResults(results, cropImageBytes.size());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return unavailableResults(cropImageBytes, "interrupted");
    } catch (IOException exception) {
      return unavailableResults(cropImageBytes, "unavailable");
    }
  }

  private byte[] multipartBody(String boundary, List<byte[]> imageBytesList) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (int index = 0; index < imageBytesList.size(); index += 1) {
      byte[] imageBytes = imageBytesList.get(index);
      output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
      output.write(("Content-Disposition: form-data; name=\"files\"; filename=\"crop-" + index + ".jpg\"\r\n").getBytes(StandardCharsets.UTF_8));
      output.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      if (imageBytes != null) {
        output.write(imageBytes);
      }
      output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return output.toByteArray();
  }

  private List<FieldRegionOcrResult> paddedResults(JsonNode results, int expectedSize) {
    java.util.ArrayList<FieldRegionOcrResult> values = new java.util.ArrayList<>();
    for (int index = 0; index < expectedSize; index += 1) {
      JsonNode item = index < results.size() ? results.get(index) : null;
      if (item == null || item.isMissingNode() || item.isNull()) {
        values.add(FieldRegionOcrResult.unavailable("missing_result"));
        continue;
      }
      values.add(new FieldRegionOcrResult(
          item.path("text").asText(""),
          normalizeConfidence(item.path("confidence").asDouble(0)),
          item.path("status").asText("available")
      ));
    }
    return List.copyOf(values);
  }

  private List<FieldRegionOcrResult> unavailableResults(List<byte[]> cropImageBytes, String status) {
    int size = cropImageBytes == null ? 0 : cropImageBytes.size();
    java.util.ArrayList<FieldRegionOcrResult> results = new java.util.ArrayList<>();
    for (int index = 0; index < size; index += 1) {
      results.add(FieldRegionOcrResult.unavailable(status));
    }
    return List.copyOf(results);
  }

  private double normalizeConfidence(double value) {
    if (value <= 1) {
      return Math.round(value * 100);
    }
    return Math.round(value);
  }

  private String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
