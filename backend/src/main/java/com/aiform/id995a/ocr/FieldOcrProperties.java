package com.aiform.id995a.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "field-ocr")
public record FieldOcrProperties(
    boolean enabled,
    String baseUrl,
    int timeoutSeconds
) {

  public FieldOcrProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:18092" : baseUrl;
    timeoutSeconds = Math.max(2, timeoutSeconds);
  }
}
