package com.aiform.id995a.llm;

public record LlmModelOption(
    String id,
    String label,
    String model,
    String provider,
    boolean available,
    boolean selected,
    String unavailableReason
) {}
