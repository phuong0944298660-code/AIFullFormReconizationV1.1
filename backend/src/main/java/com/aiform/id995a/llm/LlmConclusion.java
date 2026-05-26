package com.aiform.id995a.llm;

public record LlmConclusion(
    boolean enabled,
    String status,
    String model,
    String text
) {}
