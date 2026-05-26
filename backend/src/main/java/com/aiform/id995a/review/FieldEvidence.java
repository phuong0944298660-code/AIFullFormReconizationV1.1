package com.aiform.id995a.review;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public record FieldEvidence(
    Map<String, Boolean> present,
    Map<String, Double> confidence,
    String extractedText
) {

  public static FieldEvidence allPresent(String... fieldKeys) {
    Map<String, Boolean> present = new HashMap<>();
    Map<String, Double> confidence = new HashMap<>();
    Arrays.stream(fieldKeys).forEach(key -> {
      present.put(key, true);
      confidence.put(key, 1.0);
    });
    return new FieldEvidence(Map.copyOf(present), Map.copyOf(confidence), "");
  }

  public boolean isPresent(String fieldKey) {
    return present.getOrDefault(fieldKey, false);
  }

  public double confidenceOf(String fieldKey) {
    return confidence.getOrDefault(fieldKey, 0.0);
  }
}
