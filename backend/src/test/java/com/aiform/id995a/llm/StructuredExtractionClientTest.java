package com.aiform.id995a.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.ocr.RenderedOcrPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class StructuredExtractionClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void buildsOpenAiCompatibleVisionJsonRequestForRenderedPages() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}")),
        objectMapper
    );

    JsonNode payload = client.buildRequestPayload(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(payload.path("model").asText()).isEqualTo("Qwen3.6-35B-A3B");
    assertThat(payload.path("response_format").path("type").asText()).isEqualTo("json_object");
    assertThat(payload.path("enable_thinking").asBoolean()).isFalse();
    assertThat(payload.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    assertThat(payload.path("messages").get(1).path("content").get(1).path("type").asText()).isEqualTo("image_url");
    assertThat(payload.path("messages").get(1).path("content").get(1).path("image_url").path("url").asText())
        .isEqualTo("data:image/png;base64,abc123");
    assertThat(payload.toString()).contains("Do not use a predefined field list");
    assertThat(payload.toString()).contains("For signatures, transcribe the visible handwritten signature text");
    assertThat(payload.toString()).contains("Do not return present for signatures");
    assertThat(payload.toString()).contains("top-level _confidence object");
    assertThat(payload.toString()).contains("top-level _field_evidence object");
    assertThat(payload.toString()).contains("char_confidences");
    assertThat(payload.toString()).contains("no_applicant_input");
    assertThat(payload.toString()).contains("such as 有/没有");
    assertThat(payload.toString()).contains("{\\\"pillow\\\":\\\"没有\\\"}");
    assertThat(payload.toString()).contains("Use true/false only for a standalone checkbox");
    assertThat(payload.toString()).contains("3名成人");
    assertThat(payload.toString()).contains("applicant-written number");
    assertThat(payload.toString()).contains("Do not correct, complete, normalize, or infer handwritten values");
    assertThat(payload.toString()).contains("visible mial");
    assertThat(payload.toString()).contains("not mail, hotmail, gmail, yahoo");
    assertThat(payload.toString()).contains("distinguish F from T by strokes");
    assertThat(payload.toString()).contains("Do not assume the prefix from the form type");
    assertThat(payload.toString()).contains("excluded_marks");
    assertThat(payload.toString()).contains("If smudged, crossed-out, erased, or correction marks are mixed into a filled value");
    assertThat(payload.toString()).contains("treat those marks as not filled");
    assertThat(payload.toString()).contains("Separate servant room");
    assertThat(payload.toString()).contains("Return the selected option text exactly as visible");
    assertThat(payload.toString()).contains("Particulars of household members");
    assertThat(payload.toString()).contains("return every visible cell in that row");
    assertThat(payload.toString()).contains("HK identity card no. Yes/No rows");
    assertThat(payload.toString()).contains("禁止纠正、补全、规范化、按常识推断手写值");
  }

  @Test
  void parsesJsonOnlyModelResponseIntoStructuredData() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("```json\n{\"page_1\":{\"travel_document_no\":\"PH88342115\"}}\n```")),
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(result.model()).isEqualTo("Qwen3.6-35B-A3B");
    assertThat(result.data().at("/page_1/travel_document_no").asText()).isEqualTo("PH88342115");
    assertThat(result.rawText()).contains("travel_document_no");
  }

  @Test
  void usesSelectedModelProfileForDashScopeCompatibleRequest() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}"));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "local-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );
    LlmModelProfile profile = new LlmModelProfile(
        "dashscope-qwen3.6-35b-a3b",
        "云原生模型",
        "qwen3.6-35b-a3b",
        "DashScope OpenAI-compatible",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "dashscope-key",
        true,
        false,
        ""
    );

    JsonNode payload = client.buildRequestPayload(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400)),
        profile
    );
    StructuredExtractionResult result = client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400)),
        ExtractionProgressListener.NOOP,
        profile
    );

    assertThat(payload.path("model").asText()).isEqualTo("qwen3.6-35b-a3b");
    assertThat(payload.path("enable_thinking").asBoolean()).isTrue();
    assertThat(payload.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isTrue();
    assertThat(result.model()).isEqualTo("qwen3.6-35b-a3b");
    assertThat(httpClient.lastRequest().uri().toString())
        .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
    assertThat(httpClient.lastRequest().headers().firstValue("Authorization")).contains("Bearer dashscope-key");
  }

  @Test
  void transcribesFieldCropsWithExactAddressNumberInstructions() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(jsonResponse("""
        {"results":[{"page":1,"path":"correspondence_address","text":"香港中環德輔道中NO88號國金中心二期2802室","address_number_fragment":"NO88","confidence":92,"status":"ok"}]}
        """));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    List<FieldCropTranscriptionResult> results = client.transcribeFieldCrops(
        "sample.pdf",
        List.of(new FieldCropTranscriptionRequest(
            1,
            "correspondence_address",
            "Correspondence address",
            "香港中環德輔道中168號國金中心二期2802室",
            new byte[] {1, 2, 3},
            "data:image/jpeg;base64,crop123"
        )),
        new LlmModelProfile(
            "local-qwen3.6-35b-a3b",
            "本地模型",
            "Qwen3.6-35B-A3B",
            "OpenAI-compatible local gateway",
            "https://apie.zhisuaninfo.com/v1",
            "test-key",
            false,
            true,
            ""
        )
    );

    JsonNode payload = objectMapper.readTree(httpClient.lastRequestBody());
    String requestText = payload.toString();
    assertThat(results).hasSize(1);
    assertThat(results.get(0).text()).isEqualTo("香港中環德輔道中NO88號國金中心二期2802室");
    assertThat(results.get(0).addressNumberFragment()).isEqualTo("NO88");
    assertThat(payload.path("messages").get(1).path("content").get(1).path("image_url").path("url").asText())
        .isEqualTo("data:image/jpeg;base64,crop123");
    assertThat(requestText).contains("Transcribe only the visible applicant-filled value");
    assertThat(requestText).contains("Do not correct, complete, normalize, or infer");
    assertThat(requestText).contains("if the visible handwriting reads mial");
    assertThat(requestText).contains("do not change it to mail, hotmail, gmail, or yahoo");
    assertThat(requestText).contains("employment contract number");
    assertThat(requestText).contains("distinguish uppercase F from T");
    assertThat(requestText).contains("Do not assume a prefix");
    assertThat(requestText).contains("do not drop year digits such as 2026");
    assertThat(requestText).contains("If a crossed-out, smudged, erased, or correction mark appears anywhere");
    assertThat(requestText).contains("before, between, over, or after normal characters");
    assertThat(requestText).contains("Checkbox/option rows");
    assertThat(requestText).contains("clear intentional selection mark");
    assertThat(requestText).contains("Preserve address number prefixes such as No, NO, no, N0 exactly");
    assertThat(requestText).contains("For address crops, read every visible applicant-filled address line");
    assertThat(requestText).contains("do not stop after the first line");
    assertThat(requestText).contains("copy every visible digit after No");
    assertThat(requestText).contains("Exclude smudged, crossed-out, erased, or correction marks");
    assertThat(requestText).contains("excluded_marks");
    assertThat(requestText).contains("current_first_pass_value");
  }

  @Test
  void parsesExcludedMarksFromFieldCropTranscriptionResponse() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(jsonResponse("""
        {"results":[{"page":1,"path":"address","text":"No88","address_number_fragment":"No88","excluded_marks":[{"text":"X","reason":"smudged"}],"confidence":91,"status":"ok"}]}
        """));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    List<FieldCropTranscriptionResult> results = client.transcribeFieldCrops(
        "sample.pdf",
        List.of(new FieldCropTranscriptionRequest(
            1,
            "address",
            "Address",
            "NoX88",
            new byte[] {1},
            "data:image/jpeg;base64,crop123"
        )),
        new LlmModelProfile(
            "local-qwen3.6-35b-a3b",
            "local",
            "Qwen3.6-35B-A3B",
            "OpenAI-compatible local gateway",
            "https://apie.zhisuaninfo.com/v1",
            "test-key",
            false,
            true,
            ""
        )
    );

    assertThat(results).hasSize(1);
    assertThat(results.get(0).excludedMarks().get(0).path("text").asText()).isEqualTo("X");
    assertThat(results.get(0).excludedMarks().get(0).path("reason").asText()).isEqualTo("smudged");
  }

  @Test
  void retriesOnceWhenModelReturnsEmptyJsonObject() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(List.of(
        jsonResponse("{}"),
        jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"},\"_field_evidence\":{\"page_1\":{\"surname_en\":{\"label\":\"Surname in English\",\"value_bbox\":[10,20,150,40]}}}}")
    ));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(httpClient.sendCount()).isEqualTo(2);
    assertThat(result.data().at("/page_1/surname_en").asText()).isEqualTo("CHAN");
  }

  @Test
  void retriesOnceWhenLlmConnectionClosesBeforeResponseHeaders() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(List.of(
        new java.io.IOException("HTTP/1.1 header parser received no bytes"),
        jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"},\"_field_evidence\":{\"page_1\":{\"surname_en\":{\"label\":\"Surname in English\",\"value_bbox\":[10,20,150,40]}}}}")
    ));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(httpClient.sendCount()).isEqualTo(2);
    assertThat(result.data().at("/page_1/surname_en").asText()).isEqualTo("CHAN");
  }

  @Test
  void doesNotRetryWhenModelMarksPageAsHavingNoApplicantInput() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(jsonResponse("{\"page_1\":{\"no_applicant_input\":true}}"));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "blank-page.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(httpClient.sendCount()).isEqualTo(1);
    assertThat(result.data().at("/page_1/no_applicant_input").asBoolean()).isTrue();
  }

  @Test
  void doesNotRetryWhenModelReturnsFieldsWithoutPerFieldEvidence() throws Exception {
    StubHttpClient httpClient = new StubHttpClient(List.of(
        jsonResponse("{\"page_1\":{\"name\":\"Alice Zhang\"},\"_field_evidence\":{\"page_1\":{\"label\":\"Whole page\",\"value_bbox\":[0,0,100,100]}}}"),
        jsonResponse("{\"page_1\":{\"name\":\"Alice Zhang\"},\"_field_evidence\":{\"page_1\":{\"name\":{\"label\":\"Name\",\"value_bbox\":[10,20,160,50]}}}}")
    ));
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        httpClient,
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.png",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(httpClient.sendCount()).isEqualTo(1);
    assertThat(result.data().at("/page_1/name").asText()).isEqualTo("Alice Zhang");
  }

  @Test
  void acceptsCommonLenientJsonFromModelResponses() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"selected_options\":[\"A\",],}}")),
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(result.data().at("/page_1/selected_options/0").asText()).isEqualTo("A");
  }

  @Test
  void extractsJsonFromReasoningWhenCompatibleApiReturnsNullContent() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(reasoningResponse("I inspected the page.\n{\"page_1\":{\"name\":\"Alice Zhang\"}}")),
        objectMapper
    );

    StructuredExtractionResult result = client.extract(
        "sample.png",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400))
    );

    assertThat(result.data().at("/page_1/name").asText()).isEqualTo("Alice Zhang");
    assertThat(result.rawText()).doesNotContain("I inspected the page");
  }

  @Test
  void throwsReadableMessageWhenLlmApiKeyIsMissing() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{}")),
        objectMapper
    );

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.extract("sample.pdf", List.of()))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Missing LLM API key");
  }

  @Test
  void choosesPageConcurrencyFromActualPageCountAndConfiguredMaximum() throws Exception {
    StructuredExtractionClient defaultClient = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}")),
        objectMapper
    );
    StructuredExtractionClient widerClient = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 8),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}")),
        objectMapper
    );

    assertThat(defaultClient.pageConcurrency(1)).isEqualTo(1);
    assertThat(defaultClient.pageConcurrency(2)).isEqualTo(2);
    assertThat(defaultClient.pageConcurrency(3)).isEqualTo(3);
    assertThat(defaultClient.pageConcurrency(4)).isEqualTo(4);
    assertThat(defaultClient.pageConcurrency(5)).isEqualTo(4);
    assertThat(defaultClient.pageConcurrency(7)).isEqualTo(4);
    assertThat(widerClient.pageConcurrency(12)).isEqualTo(4);
  }

  @Test
  void reportsRealPageStartAndCompletionEvents() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}")),
        objectMapper
    );
    List<String> events = new CopyOnWriteArrayList<>();

    client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400)),
        new ExtractionProgressListener() {
          @Override
          public void pageStarted(int page) {
            events.add("started:" + page);
          }

          @Override
          public void pageCompleted(int page) {
            events.add("completed:" + page);
          }
        }
    );

    assertThat(events).containsExactly("started:1", "completed:1");
  }

  @Test
  void reportsPerPageAttemptTimingEvents() throws Exception {
    StructuredExtractionClient client = new StructuredExtractionClient(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "Qwen3.6-35B-A3B", 4096, 60, 4),
        new StubHttpClient(jsonResponse("{\"page_1\":{\"surname_en\":\"CHAN\"}}")),
        objectMapper
    );
    List<String> events = new CopyOnWriteArrayList<>();

    client.extract(
        "sample.pdf",
        List.of(new RenderedOcrPage(1, new byte[] {1, 2, 3}, "data:image/png;base64,abc123", 1000, 1400)),
        new ExtractionProgressListener() {
          @Override
          public void pageAttemptStarted(int page, int attempt, String reason) {
            events.add("attempt-started:" + page + ":" + attempt + ":" + reason);
          }

          @Override
          public void pageAttemptCompleted(int page, int attempt, String reason, long elapsedMillis) {
            events.add("attempt-completed:" + page + ":" + attempt + ":" + reason + ":" + (elapsedMillis >= 0));
          }
        }
    );

    assertThat(events).containsExactly(
        "attempt-started:1:1:initial",
        "attempt-completed:1:1:initial:true"
    );
  }

  private String jsonResponse(String content) throws Exception {
    return objectMapper.writeValueAsString(Map.of(
        "choices", List.of(Map.of(
            "finish_reason", "stop",
            "message", Map.of("content", content)
        )),
        "usage", Map.of("total_tokens", 128)
    ));
  }

  private String reasoningResponse(String reasoning) throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode choice = root.putArray("choices").addObject();
    choice.put("finish_reason", "stop");
    com.fasterxml.jackson.databind.node.ObjectNode message = choice.putObject("message");
    message.put("role", "assistant");
    message.putNull("content");
    message.put("reasoning", reasoning);
    root.putObject("usage").put("total_tokens", 128);
    return objectMapper.writeValueAsString(root);
  }

  private static final class StubHttpClient extends HttpClient {
    private final List<?> outcomes;
    private final AtomicInteger sendCount = new AtomicInteger();
    private final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    private StubHttpClient(String body) {
      this(List.of(body));
    }

    private StubHttpClient(List<?> outcomes) {
      this.outcomes = List.copyOf(outcomes);
    }

    private int sendCount() {
      return sendCount.get();
    }

    private HttpRequest lastRequest() {
      return lastRequest.get();
    }

    private String lastRequestBody() {
      return lastRequestBody.get();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      try {
        return SSLContext.getDefault();
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws java.io.IOException {
      lastRequest.set(request);
      lastRequestBody.set(readBody(request));
      int index = sendCount.getAndIncrement();
      Object outcome = outcomes.get(Math.min(index, outcomes.size() - 1));
      if (outcome instanceof java.io.IOException exception) {
        throw exception;
      }
      String body = (String) outcome;
      return (HttpResponse<T>) new StubHttpResponse(request, body);
    }

    private String readBody(HttpRequest request) {
      BodyCaptureSubscriber subscriber = new BodyCaptureSubscriber();
      request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(subscriber));
      return subscriber.body();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      try {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler));
      } catch (Exception exception) {
        return CompletableFuture.failedFuture(exception);
      }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
      try {
        return CompletableFuture.completedFuture(send(request, responseBodyHandler));
      } catch (Exception exception) {
        return CompletableFuture.failedFuture(exception);
      }
    }

    private static final class BodyCaptureSubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
      private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

      @Override
      public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(java.nio.ByteBuffer item) {
        byte[] bytes = new byte[item.remaining()];
        item.get(bytes);
        output.write(bytes, 0, bytes.length);
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}

      private String body() {
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
      }
    }
  }

  private record StubHttpResponse(HttpRequest request, String body) implements HttpResponse<String> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (left, right) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
