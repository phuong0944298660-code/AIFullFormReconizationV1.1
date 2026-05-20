package com.aiform.id995a.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.rules.RuleFinding;
import com.aiform.id995a.rules.RuleReview;
import com.aiform.id995a.rules.RuleSeverity;
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
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class LlmConclusionServiceTest {

  @Test
  void returnsDeterministicFallbackWhenApiKeyIsMissing() {
    LlmConclusionService service = new LlmConclusionService(
        new LlmProperties(false, "https://apie.zhisuaninfo.com/v1", "", "Qwen3.6-35B-A3B", 900, 20, 4)
    );
    RuleReview review = RuleReview.from(List.of(
        new RuleFinding(
            "A9-001-SIGNATURE",
            RuleSeverity.BLOCKING,
            "必填项缺失",
            "申请人/父母/合法监护人声明未签署",
            "ID995A",
            4,
            "declarationSignature"
        )
    ));

    LlmConclusion conclusion = service.generate(review, "ID995A_demo.pdf");

    assertThat(conclusion.enabled()).isFalse();
    assertThat(conclusion.status()).isEqualTo("disabled");
    assertThat(conclusion.text()).contains("1处不通过");
    assertThat(conclusion.text()).contains("申请人/父母/合法监护人声明未签署");
  }

  @Test
  void usesModelOutputWhenItMatchesPlainTextConclusionFormat() throws Exception {
    String modelText = "整体结论：1处不通过。\n不通过理由：\n- 英文姓未填写（A1-001-SURNAME，第 1 页）";
    LlmConclusionService service = new LlmConclusionService(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "test-model", 2048, 20, 4),
        new StubHttpClient(jsonResponse(modelText)),
        new ObjectMapper()
    );

    LlmConclusion conclusion = service.generate(sampleReview(), "ID995A_demo.pdf");

    assertThat(conclusion.status()).isEqualTo("ok");
    assertThat(conclusion.text()).isEqualTo(modelText);
  }

  @Test
  void fallsBackWhenModelOutputAddsMarkdownOrAdvice() throws Exception {
    String modelText = "# 📋 申请审核结果\n\n以下是 Markdown 版本：\n| 项目 | 建议 |\n| --- | --- |\n";
    LlmConclusionService service = new LlmConclusionService(
        new LlmProperties(true, "https://apie.zhisuaninfo.com/v1", "test-key", "test-model", 2048, 20, 4),
        new StubHttpClient(jsonResponse(modelText)),
        new ObjectMapper()
    );

    LlmConclusion conclusion = service.generate(sampleReview(), "ID995A_demo.pdf");

    assertThat(conclusion.status()).isEqualTo("format_fallback");
    assertThat(conclusion.text()).contains("1处不通过");
    assertThat(conclusion.text()).doesNotContain("Markdown");
  }

  private RuleReview sampleReview() {
    return RuleReview.from(List.of(
        new RuleFinding(
            "A1-001-SURNAME",
            RuleSeverity.BLOCKING,
            "必填项缺失",
            "英文姓未填写",
            "ID995A",
            1,
            "surnameEn"
        )
    ));
  }

  private String jsonResponse(String content) throws Exception {
    return new ObjectMapper().writeValueAsString(Map.of(
        "choices", List.of(Map.of(
            "finish_reason", "stop",
            "message", Map.of("content", content)
        )),
        "usage", Map.of("total_tokens", 128)
    ));
  }

  private static final class StubHttpClient extends HttpClient {
    private final String body;

    private StubHttpClient(String body) {
      this.body = body;
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
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      return (HttpResponse<T>) new StubHttpResponse(request, body);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
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
