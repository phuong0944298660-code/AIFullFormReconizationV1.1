package com.aiform.id995a.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagServiceProperties(
    boolean enabled,
    String baseUrl,
    int timeoutSeconds,
    int topK
) {}
