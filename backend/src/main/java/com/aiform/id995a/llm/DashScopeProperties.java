package com.aiform.id995a.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dashscope")
public record DashScopeProperties(
    String baseUrl,
    String apiKey,
    String model,
    boolean enableThinking
) {}
