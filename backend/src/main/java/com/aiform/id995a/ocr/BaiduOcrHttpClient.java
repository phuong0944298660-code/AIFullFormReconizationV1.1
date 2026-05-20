package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BaiduOcrHttpClient implements BaiduOcrGateway {

  private static final URI TOKEN_URI = URI.create("https://aip.baidubce.com/oauth/2.0/token");
  private static final String OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/accurate";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String apiKey;
  private final String secretKey;
  private final String authorizationHeader;
  private final Duration timeout;
  private final String languageType;
  private final boolean detectDirection;
  private final boolean vertexesLocation;
  private final boolean paragraph;
  private final boolean probability;
  private final boolean charProbability;
  private final boolean multidirectionalRecognize;
  private final URI ocrUri;
  private final URI tokenUri;

  private String cachedAccessToken = "";
  private Instant tokenExpiresAt = Instant.EPOCH;

  @Autowired
  public BaiduOcrHttpClient(
      ObjectMapper objectMapper,
      @Value("${ocr.baidu.api-key:}") String apiKey,
      @Value("${ocr.baidu.secret-key:}") String secretKey,
      @Value("${ocr.baidu.authorization:}") String authorizationHeader,
      @Value("${ocr.baidu.timeout-seconds:60}") long timeoutSeconds,
      @Value("${ocr.baidu.language-type:CHN_ENG}") String languageType,
      @Value("${ocr.baidu.detect-direction:false}") boolean detectDirection,
      @Value("${ocr.baidu.vertexes-location:false}") boolean vertexesLocation,
      @Value("${ocr.baidu.paragraph:false}") boolean paragraph,
      @Value("${ocr.baidu.probability:false}") boolean probability,
      @Value("${ocr.baidu.char-probability:false}") boolean charProbability,
      @Value("${ocr.baidu.multidirectional-recognize:false}") boolean multidirectionalRecognize
  ) {
    this(
        objectMapper,
        apiKey,
        secretKey,
        authorizationHeader,
        timeoutSeconds,
        languageType,
        detectDirection,
        vertexesLocation,
        paragraph,
        probability,
        charProbability,
        multidirectionalRecognize,
        URI.create(OCR_URL),
        TOKEN_URI,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    );
  }

  BaiduOcrHttpClient(
      ObjectMapper objectMapper,
      String apiKey,
      String secretKey,
      String authorizationHeader,
      long timeoutSeconds,
      String languageType,
      boolean detectDirection,
      boolean vertexesLocation,
      boolean paragraph,
      boolean probability,
      boolean charProbability,
      boolean multidirectionalRecognize,
      URI ocrUri,
      URI tokenUri,
      HttpClient httpClient
  ) {
    this.objectMapper = objectMapper;
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.secretKey = secretKey == null ? "" : secretKey.trim();
    this.authorizationHeader = normalizeAuthorizationHeader(authorizationHeader);
    this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
    this.languageType = languageType == null || languageType.isBlank() ? "CHN_ENG" : languageType.trim();
    this.detectDirection = detectDirection;
    this.vertexesLocation = vertexesLocation;
    this.paragraph = paragraph;
    this.probability = probability;
    this.charProbability = charProbability;
    this.multidirectionalRecognize = multidirectionalRecognize;
    this.ocrUri = ocrUri;
    this.tokenUri = tokenUri;
    this.httpClient = httpClient;
  }

  @Override
  public String recognizePng(byte[] pagePngBytes) throws IOException {
    if (pagePngBytes == null || pagePngBytes.length == 0) {
      throw new IOException("Baidu OCR received an empty page image.");
    }

    String body = form(
        "image", Base64.getEncoder().encodeToString(pagePngBytes),
        "language_type", languageType,
        "detect_direction", Boolean.toString(detectDirection),
        "vertexes_location", Boolean.toString(vertexesLocation),
        "paragraph", Boolean.toString(paragraph),
        "probability", Boolean.toString(probability),
        "char_probability", Boolean.toString(charProbability),
        "multidirectional_recognize", Boolean.toString(multidirectionalRecognize)
    );

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(ocrRequestUri())
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (!authorizationHeader.isBlank()) {
      requestBuilder.header("Authorization", authorizationHeader);
    }
    return send(requestBuilder.build(), "Baidu OCR");
  }

  private URI ocrRequestUri() throws IOException {
    if (!authorizationHeader.isBlank()) {
      return ocrUri;
    }
    String separator = ocrUri.getRawQuery() == null ? "?" : "&";
    return URI.create(ocrUri + separator + "access_token=" + encode(accessToken()));
  }

  private synchronized String accessToken() throws IOException {
    if (!cachedAccessToken.isBlank() && Instant.now().isBefore(tokenExpiresAt.minusSeconds(60))) {
      return cachedAccessToken;
    }
    if (apiKey.isBlank() || secretKey.isBlank()) {
      throw new IOException(
          "Missing Baidu OCR credentials. Set BAIDU_OCR_AUTHORIZATION or BAIDU_OCR_API_KEY and BAIDU_OCR_SECRET_KEY."
      );
    }

    String body = form(
        "grant_type", "client_credentials",
        "client_id", apiKey,
        "client_secret", secretKey
    );
    HttpRequest request = HttpRequest.newBuilder(tokenUri)
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();

    String responseBody = send(request, "Baidu OAuth");
    JsonNode root = objectMapper.readTree(responseBody);
    String token = root.path("access_token").asText("");
    if (token.isBlank()) {
      throw new IOException("Baidu OAuth response did not include access_token: " + responseBody);
    }
    long expiresIn = Math.max(300, root.path("expires_in").asLong(2_592_000));
    cachedAccessToken = token;
    tokenExpiresAt = Instant.now().plusSeconds(expiresIn);
    return cachedAccessToken;
  }

  private String send(HttpRequest request, String serviceName) throws IOException {
    try {
      HttpResponse<String> response = httpClient.send(
          request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
      );
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException(serviceName + " returned HTTP " + response.statusCode() + ": " + response.body());
      }
      return response.body();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException(serviceName + " request interrupted", exception);
    }
  }

  private String form(String... pairs) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < pairs.length; index += 2) {
      if (index > 0) {
        builder.append('&');
      }
      builder.append(encode(pairs[index])).append('=').append(encode(pairs[index + 1]));
    }
    return builder.toString();
  }

  private String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private String normalizeAuthorizationHeader(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isBlank()) {
      return "";
    }
    if (trimmed.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      return trimmed;
    }
    return "Bearer " + trimmed;
  }
}
