package com.aiform.id995a.llm;

import com.aiform.id995a.ocr.RenderedOcrPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StructuredExtractionClient implements StructuredExtractionGateway {

  private static final String DEFAULT_BASE_URL = "https://apie.zhisuaninfo.com/v1";

  private final LlmProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ObjectMapper lenientObjectMapper;

  @Autowired
  public StructuredExtractionClient(LlmProperties properties, ObjectMapper objectMapper) {
    this(properties, HttpClient.newHttpClient(), objectMapper);
  }

  StructuredExtractionClient(LlmProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
    this.properties = properties;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.lenientObjectMapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .build();
  }

  @Override
  public StructuredExtractionResult extract(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener
  ) throws IOException {
    return extract(filename, pages, progressListener, defaultProfile());
  }

  @Override
  public StructuredExtractionResult extract(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener,
      LlmModelProfile modelProfile
  ) throws IOException {
    LlmModelProfile profile = modelProfile == null ? defaultProfile() : modelProfile;
    if (blank(profile.apiKey())) {
      throw new IOException("Missing LLM API key. Set LLM_API_KEY.");
    }
    ExtractionProgressListener listener = progressListener == null ? ExtractionProgressListener.NOOP : progressListener;
    try {
      List<PageExtraction> pageExtractions = extractPages(filename, pages, listener, profile);
      ObjectNode combined = objectMapper.createObjectNode();
      combined.put("source_file", filename == null || filename.isBlank() ? "uploaded-document" : filename);
      combined.put("total_pages", pages.size());
      ObjectNode combinedConfidence = combined.putObject("_confidence");
      ObjectNode combinedEvidence = combined.putObject("_field_evidence");
      StringBuilder rawText = new StringBuilder();

      boolean hasAnyPageFields = false;
      for (PageExtraction pageExtraction : pageExtractions) {
        String pageKey = "page_" + pageExtraction.page();
        JsonNode pageData = pageExtraction.response().data().path(pageKey);
        if (!pageData.isObject() && !pageData.isArray()) {
          pageData = objectMapper.createObjectNode();
        }
        if (!pageData.isEmpty()) {
          hasAnyPageFields = true;
        }
        combined.set(pageKey, pageData);
        mergeMetadataPage(combinedConfidence, pageExtraction.response().data().path("_confidence"), pageKey);
        mergeMetadataPage(combinedEvidence, pageExtraction.response().data().path("_field_evidence"), pageKey);
        rawText.append("/* ").append(pageKey).append(" */\n").append(pageExtraction.response().rawText()).append('\n');
      }
      if (!hasAnyPageFields && !pages.isEmpty()) {
        throw new IOException("LLM returned empty structured JSON after retry.");
      }
      return new StructuredExtractionResult(combined, rawText.toString(), profile.model());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("LLM request interrupted.", exception);
    }
  }

  private List<PageExtraction> extractPages(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener,
      LlmModelProfile profile
  ) throws IOException, InterruptedException {
    int concurrency = pageConcurrency(pages.size());
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    try {
      List<Future<PageExtraction>> futures = new ArrayList<>();
      for (RenderedOcrPage page : pages) {
        futures.add(executor.submit(() -> extractPage(filename, pages.size(), page, progressListener, profile)));
      }
      List<PageExtraction> results = new ArrayList<>();
      for (Future<PageExtraction> future : futures) {
        try {
          results.add(future.get());
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          if (cause instanceof IOException ioException) {
            throw ioException;
          }
          if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
          }
          throw new IOException("LLM page extraction failed.", cause);
        }
      }
      return results.stream()
          .sorted(Comparator.comparingInt(PageExtraction::page))
          .toList();
    } finally {
      executor.shutdownNow();
    }
  }

  private PageExtraction extractPage(
      String filename,
      int totalPages,
      RenderedOcrPage page,
      ExtractionProgressListener progressListener,
      LlmModelProfile profile
  ) throws IOException {
    progressListener.pageStarted(page.page());
    try {
      ExtractionResponse extraction = sendPageAttempt(filename, totalPages, page, progressListener, profile, 1, "initial", false);
      if (isNoApplicantInputPage(extraction.data(), page)) {
        progressListener.pageCompleted(page.page());
        return new PageExtraction(page.page(), extraction);
      }
      if (isEmptyExtraction(extraction.data(), List.of(page))) {
        extraction = sendPageAttempt(filename, totalPages, page, progressListener, profile, 2, "empty_retry", true);
      }
      if (isEmptyExtraction(extraction.data(), List.of(page))) {
        extraction = new ExtractionResponse(objectMapper.createObjectNode(), extraction.rawText());
      }
      progressListener.pageCompleted(page.page());
      return new PageExtraction(page.page(), extraction);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      progressListener.pageFailed(page.page(), "LLM request interrupted.");
      throw new IOException("LLM request interrupted.", exception);
    } catch (IOException | RuntimeException exception) {
      progressListener.pageFailed(page.page(), exception.getMessage());
      throw exception;
    }
  }

  private ExtractionResponse sendPageAttempt(
      String filename,
      int totalPages,
      RenderedOcrPage page,
      ExtractionProgressListener progressListener,
      LlmModelProfile profile,
      int attempt,
      String reason,
      boolean retryAfterEmptyResponse
  ) throws IOException, InterruptedException {
    long startedAt = System.nanoTime();
    progressListener.pageAttemptStarted(page.page(), attempt, reason);
    try {
      ExtractionResponse response = sendExtractionRequest(
          buildRequestPayload(filename, List.of(page), retryAfterEmptyResponse, totalPages, profile),
          profile
      );
      progressListener.pageAttemptCompleted(page.page(), attempt, reason, elapsedMillisSince(startedAt));
      return response;
    } catch (InterruptedException exception) {
      progressListener.pageAttemptFailed(page.page(), attempt, reason, elapsedMillisSince(startedAt), "LLM request interrupted.");
      throw exception;
    } catch (IOException | RuntimeException exception) {
      progressListener.pageAttemptFailed(page.page(), attempt, reason, elapsedMillisSince(startedAt), exception.getMessage());
      throw exception;
    }
  }

  int pageConcurrency(int pageCount) {
    int configuredMaximum = Math.max(1, properties.pageConcurrency());
    int natural = switch (pageCount) {
      case 0, 1 -> 1;
      case 2 -> 2;
      case 3 -> 3;
      default -> 4;
    };
    return Math.max(1, Math.min(configuredMaximum, natural));
  }

  JsonNode buildRequestPayload(String filename, List<RenderedOcrPage> pages) {
    return buildRequestPayload(filename, pages, false, pages.size(), defaultProfile());
  }

  JsonNode buildRequestPayload(String filename, List<RenderedOcrPage> pages, LlmModelProfile profile) {
    return buildRequestPayload(filename, pages, false, pages.size(), profile == null ? defaultProfile() : profile);
  }

  private JsonNode buildRequestPayload(
      String filename,
      List<RenderedOcrPage> pages,
      boolean retryAfterEmptyResponse,
      int totalPages,
      LlmModelProfile profile
  ) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("model", blank(profile.model()) ? "Qwen3.6-35B-A3B" : profile.model());
    root.put("temperature", 0);
    root.put("max_tokens", Math.max(1024, properties.maxTokens()));
    root.put("stream", false);
    root.put("enable_thinking", profile.enableThinking());
    ObjectNode chatTemplateOptions = root.putObject("chat_template_kwargs");
    chatTemplateOptions.put("enable_thinking", profile.enableThinking());
    ObjectNode responseFormat = root.putObject("response_format");
    responseFormat.put("type", "json_object");

    ArrayNode messages = root.putArray("messages");
    ObjectNode systemMessage = messages.addObject();
    systemMessage.put("role", "system");
    systemMessage.put("content", "You are a form document understanding engine. Return valid JSON only.");

    ObjectNode userMessage = messages.addObject();
    userMessage.put("role", "user");
    ArrayNode content = userMessage.putArray("content");
    ObjectNode text = content.addObject();
    text.put("type", "text");
    text.put("text", buildPrompt(filename, pages, retryAfterEmptyResponse, totalPages));

    for (RenderedOcrPage page : pages) {
      ObjectNode image = content.addObject();
      image.put("type", "image_url");
      ObjectNode imageUrl = image.putObject("image_url");
      imageUrl.put("url", page.sourceImageDataUrl());
      imageUrl.put("detail", "auto");
    }

    return root;
  }

  private ExtractionResponse sendExtractionRequest(JsonNode payload, LlmModelProfile profile) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(trimTrailingSlash(profile.baseUrl()) + "/chat/completions"))
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(Duration.ofSeconds(Math.max(10, properties.timeoutSeconds())))
        .header("Authorization", "Bearer " + profile.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> response = sendWithTransientTransportRetry(request);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("LLM HTTP " + response.statusCode() + ": " + truncate(response.body(), 600));
    }

    String rawText = extractMessageContent(response.body());
    JsonNode data = readModelJson(extractJson(rawText));
    return new ExtractionResponse(data, rawText);
  }

  private HttpResponse<String> sendWithTransientTransportRetry(HttpRequest request)
      throws IOException, InterruptedException {
    int maxAttempts = 2;
    for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException exception) {
        if (attempt >= maxAttempts || !isTransientTransportFailure(exception)) {
          throw exception;
        }
      }
    }
    throw new IOException("LLM request failed.");
  }

  private boolean isTransientTransportFailure(IOException exception) {
    Throwable current = exception;
    while (current != null) {
      String message = current.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("header parser received no bytes")
            || normalized.contains("connection reset")
            || normalized.contains("connection closed")
            || normalized.contains("closed before")
            || normalized.contains("unexpected end of")
            || normalized.contains("eof")) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }

  private JsonNode readModelJson(String json) throws IOException {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException strictException) {
      try {
        return lenientObjectMapper.readTree(json);
      } catch (JsonProcessingException lenientException) {
        throw new IOException(lenientException.getOriginalMessage(), lenientException);
      }
    }
  }

  private String buildPrompt(
      String filename,
      List<RenderedOcrPage> pages,
      boolean retryAfterEmptyResponse,
      int totalPages
  ) {
    StringBuilder builder = new StringBuilder();
    builder.append("Analyze these full-page form images directly and output one JSON object.\n");
    if (pages.size() == 1) {
      builder.append("The attached image is page_").append(pages.get(0).page()).append(" of ").append(totalPages)
          .append(". Return only this page's page_").append(pages.get(0).page()).append(" fields, plus source_file, total_pages, _confidence, and _field_evidence.\n");
    }
    builder.append("Do not use a predefined field list, manual annotations, template coordinate boxes, ROI crops, or OCR output.\n");
    builder.append("Find printed field labels, filling areas, handwriting, typed values, checked boxes, signatures, and photo/upload areas by visual reasoning.\n");
    builder.append("Return a compact result. Prioritize applicant-filled text, selected checkboxes, signatures, photos, and major visible blank fields. Do not enumerate every empty grid cell, every unchecked option, template instruction, explanatory paragraph, barcode, or page footer.\n");
    builder.append("Use nearby printed labels as JSON keys, normalized to lower_snake_case English where possible. Preserve Chinese or English field values exactly when visible.\n");
    builder.append("Rules:\n");
    builder.append("- Include source_file and total_pages at the top level.\n");
    builder.append("- Group page content under page_1, page_2, etc.\n");
    builder.append("- Keep page field values as plain applicant-filled values or null. Also include a top-level _confidence object mirroring page/field paths with integer confidence scores from 0 to 100.\n");
    builder.append("- Also include a top-level _field_evidence object mirroring page/field paths. For each leaf field, include label and value_bbox as normalized {x,y,width,height} coordinates for the filled area on that page image.\n");
    builder.append("- _field_evidence.page_N must be keyed by exact page_N field paths. Never put label/value_bbox directly under _field_evidence.page_N as one whole-page evidence object.\n");
    builder.append("- Example: {\"page_2\":{\"present_address\":\"Flat 7\"},\"_field_evidence\":{\"page_2\":{\"present_address\":{\"label\":\"Present address\",\"value_bbox\":{\"x\":0.20,\"y\":0.10,\"width\":0.55,\"height\":0.09}}}}}.\n");
    builder.append("- For filled handwritten, typed, or signature text, include char_confidences only for ambiguous or low-confidence characters: [{char,index,confidence,bbox}], where bbox is normalized inside the field value_bbox. Do not list every character when the value is clear; field value_bbox is enough.\n");
    builder.append("- Each leaf field value must be the applicant-filled value; if a major visible field is blank, use null.\n");
    builder.append("- If the page has no applicant-filled handwriting, typed values, selected checkboxes, signatures, photos, or other applicant input, return {\"page_N\":{\"no_applicant_input\":true}} for that page. In this case _field_evidence is not required for that page.\n");
    builder.append("- Ignore template instructions, empty borders, empty lines, barcodes, page numbers, and smudges/corrections that are not intended field values.\n");
    builder.append("- For checkbox option groups on the same row or in the same question, such as 有/没有, Yes/No, Male/Female, Married/Single, do not create one boolean field per option. Create one field named by the row/question label and set its value to the selected option text, for example {\"pillow\":\"没有\"}, {\"water_supply\":\"有\"}, {\"sex\":\"Female\"}. Use null only when no option in that group is selected.\n");
    builder.append("- Use true/false only for a standalone checkbox whose field label itself is the option statement, and name that field with checked/is_selected when the value is a checkbox state.\n");
    builder.append("- For handwritten quantity/count fill-ins embedded in printed labels, such as \"3名成人\", \"1名小孩\", \"0家庭成员需要经常照料\", set the field value to the applicant-written number (3, 1, 0). Do not output 1/0 as a presence flag unless the field itself is a standalone checkbox state.\n");
    builder.append("- For signatures, transcribe the visible handwritten signature text as the field value when readable. Do not return present for signatures. If a signature mark exists but the text cannot be read, use \"illegible_signature\"; otherwise use null.\n");
    builder.append("- For photos or pasted image areas, return \"present\" when an actual photo exists; otherwise use null.\n");
    builder.append("- Do not invent fields that are not visible on the page.\n");
    if (retryAfterEmptyResponse) {
      builder.append("The previous response was unusable because it was empty. Re-read the page and return compact visible applicant-fillable field labels and applicant-filled values. For every non-null handwritten, typed, checked, or signature value, add a matching _field_evidence.page_N.<exact_field_path>.value_bbox for that filled area when it is clear.\n");
    }
    builder.append("source_file: ").append(filename == null || filename.isBlank() ? "uploaded-document" : filename).append('\n');
    builder.append("total_pages: ").append(totalPages).append('\n');
    return builder.toString();
  }

  private void mergeMetadataPage(ObjectNode target, JsonNode sourceMetadata, String pageKey) {
    JsonNode pageMetadata = sourceMetadata.path(pageKey);
    if (!pageMetadata.isMissingNode() && !pageMetadata.isNull()) {
      target.set(pageKey, pageMetadata);
    } else if (sourceMetadata.isNumber() || sourceMetadata.isTextual() || sourceMetadata.isBoolean()) {
      target.set(pageKey, sourceMetadata);
    } else {
      target.set(pageKey, objectMapper.createObjectNode());
    }
  }

  private boolean isEmptyExtraction(JsonNode data, List<RenderedOcrPage> pages) {
    if (data == null || data.isMissingNode() || data.isNull() || data.isEmpty()) {
      return true;
    }
    for (RenderedOcrPage page : pages) {
      JsonNode pageData = data.path("page_" + page.page());
      if (pageData.isObject() && pageData.size() > 0) {
        return false;
      }
      if (pageData.isArray() && !pageData.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private boolean isNoApplicantInputPage(JsonNode data, RenderedOcrPage page) {
    if (data == null || data.isMissingNode() || data.isNull()) {
      return false;
    }
    String pageKey = "page_" + page.page();
    return data.path(pageKey).path("no_applicant_input").asBoolean(false)
        || data.path(pageKey).path("noApplicantInput").asBoolean(false)
        || data.path("no_applicant_input").asBoolean(false)
        || data.path("noApplicantInput").asBoolean(false);
  }

  private long elapsedMillisSince(long startedAtNanos) {
    return Math.max(0, Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis());
  }

  private String extractMessageContent(String responseBody) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode message = root.at("/choices/0/message");
    JsonNode content = message.path("content");
    if (!content.isMissingNode() && !content.isNull() && !content.asText().isBlank()) {
      return content.asText();
    }

    for (String fallbackField : List.of("reasoning_content", "reasoning")) {
      String extracted = extractJsonIfPresent(message.path(fallbackField).asText(""));
      if (!blank(extracted)) {
        return extracted;
      }
    }
    throw new IOException("LLM response did not include JSON content: " + truncate(responseBody, 600));
  }

  private String extractJsonIfPresent(String text) {
    try {
      return extractJson(text);
    } catch (IOException exception) {
      return "";
    }
  }

  private String extractJson(String text) throws IOException {
    String trimmed = text == null ? "" : text.trim();
    int objectStart = trimmed.indexOf('{');
    int arrayStart = trimmed.indexOf('[');
    int start;
    char endChar;
    if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
      start = objectStart;
      endChar = '}';
    } else if (arrayStart >= 0) {
      start = arrayStart;
      endChar = ']';
    } else {
      throw new IOException("LLM response did not contain JSON: " + truncate(trimmed, 600));
    }

    int end = trimmed.lastIndexOf(endChar);
    if (end < start) {
      throw new IOException("LLM response contained incomplete JSON: " + truncate(trimmed, 600));
    }
    return trimmed.substring(start, end + 1);
  }

  private String trimTrailingSlash(String value) {
    if (blank(value)) {
      return DEFAULT_BASE_URL;
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }

  private LlmModelProfile defaultProfile() {
    return new LlmModelProfile(
        LlmModelRegistry.DEFAULT_MODEL_ID,
        LlmModelRegistry.DEFAULT_MODEL_LABEL,
        blank(properties.model()) ? "Qwen3.6-35B-A3B" : properties.model(),
        "OpenAI-compatible local gateway",
        blank(properties.baseUrl()) ? DEFAULT_BASE_URL : properties.baseUrl(),
        properties.apiKey(),
        false,
        true,
        blank(properties.apiKey()) ? "缺少 LLM_API_KEY" : ""
    );
  }

  private record ExtractionResponse(JsonNode data, String rawText) {}

  private record PageExtraction(int page, ExtractionResponse response) {}
}
