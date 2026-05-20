package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class BaiduOcrHttpClientTest {

  @Test
  void usesConfiguredAuthorizationHeaderWithoutOauthAccessTokenQuery() throws Exception {
    CapturingHttpClient httpClient = new CapturingHttpClient();
    BaiduOcrHttpClient client = new BaiduOcrHttpClient(
        new ObjectMapper(),
        "",
        "",
        "Bearer test-token",
        60,
        "CHN_ENG",
        false,
        false,
        false,
        false,
        false,
        false,
        URI.create("https://example.test/ocr"),
        URI.create("https://example.test/token"),
        httpClient
    );

    client.recognizePng("png".getBytes(StandardCharsets.UTF_8));

    assertThat(httpClient.capturedRequest.uri()).isEqualTo(URI.create("https://example.test/ocr"));
    assertThat(httpClient.capturedRequest.uri().getQuery()).isNull();
    assertThat(httpClient.capturedRequest.headers().firstValue("Authorization"))
        .hasValue("Bearer test-token");
    assertThat(httpClient.sendCount).isEqualTo(1);
  }

  private static class CapturingHttpClient extends HttpClient {
    private HttpRequest capturedRequest;
    private int sendCount;

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
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
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
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      capturedRequest = request;
      sendCount += 1;
      return new FakeResponse<>(request, (T) "{\"words_result\":[]}");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler
    ) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler
    ) {
      throw new UnsupportedOperationException();
    }
  }

  private record FakeResponse<T>(HttpRequest request, T body) implements HttpResponse<T> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
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
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
