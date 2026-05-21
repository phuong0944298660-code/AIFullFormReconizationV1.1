package com.aiform.id995a.llm;

public record LlmModelProfile(
    String id,
    String label,
    String model,
    String provider,
    String baseUrl,
    String apiKey,
    boolean enableThinking,
    boolean selected,
    String unavailableReason
) {
  public boolean available() {
    return apiKey != null && !apiKey.isBlank();
  }

  public LlmModelOption toOption() {
    return new LlmModelOption(id, label, model, provider, available(), selected, unavailableReason);
  }
}
