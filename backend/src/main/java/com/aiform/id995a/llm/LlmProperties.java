package com.aiform.id995a.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    String model,
    int maxTokens,
    int timeoutSeconds,
    int pageConcurrency
) {}
