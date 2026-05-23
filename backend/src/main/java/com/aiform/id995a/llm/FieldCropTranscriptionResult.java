package com.aiform.id995a.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record FieldCropTranscriptionResult(
    int page,
    String path,
    String text,
    String addressNumberFragment,
    double confidence,
    String status,
    JsonNode excludedMarks
) {

  public FieldCropTranscriptionResult(
      int page,
      String path,
      String text,
      String addressNumberFragment,
      double confidence,
      String status
  ) {
    this(page, path, text, addressNumberFragment, confidence, status, NullNode.getInstance());
  }

  public FieldCropTranscriptionResult {
    path = path == null ? "" : path;
    text = text == null ? "" : text;
    addressNumberFragment = addressNumberFragment == null ? "" : addressNumberFragment;
    confidence = Math.max(0, Math.min(100, confidence));
    status = status == null || status.isBlank() ? "not_run" : status;
    excludedMarks = excludedMarks == null ? NullNode.getInstance() : excludedMarks;
  }

  public String textWithoutExcludedMarks() {
    String value = text.trim();
    if (value.isBlank() || !excludedMarks.isArray() || excludedMarks.isEmpty()) {
      return value;
    }
    List<String> characters = splitCodePoints(value);
    Set<Integer> indexes = new HashSet<>();
    for (JsonNode mark : excludedMarks) {
      int index = integer(firstExisting(mark, "start_index", "index", "value_index"), -1);
      int length = Math.max(1, integer(firstExisting(mark, "length", "char_count"), 1));
      for (int offset = 0; index >= 0 && offset < length; offset += 1) {
        addIndex(indexes, index + offset, characters.size());
      }
    }
    String filtered = removeIndexes(characters, indexes);
    for (JsonNode mark : excludedMarks) {
      String markText = firstExisting(mark, "text", "char", "value").asText("");
      if (markText.isBlank()) {
        continue;
      }
      if (countOccurrences(filtered, markText) == 1) {
        filtered = filtered.replace(markText, "");
      }
    }
    return filtered.trim();
  }

  private static List<String> splitCodePoints(String text) {
    return text.codePoints()
        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
        .toList();
  }

  private static void addIndex(Set<Integer> indexes, int index, int valueLength) {
    if (index >= 0 && index < valueLength) {
      indexes.add(index);
    }
  }

  private static String removeIndexes(List<String> characters, Set<Integer> excludedIndexes) {
    if (excludedIndexes.isEmpty()) {
      return String.join("", characters);
    }
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < characters.size(); index += 1) {
      if (!excludedIndexes.contains(index)) {
        builder.append(characters.get(index));
      }
    }
    return builder.toString();
  }

  private static int countOccurrences(String value, String needle) {
    if (value.isBlank() || needle.isBlank()) {
      return 0;
    }
    int count = 0;
    int position = value.indexOf(needle);
    while (position >= 0) {
      count += 1;
      position = value.indexOf(needle, position + needle.length());
    }
    return count;
  }

  private static JsonNode firstExisting(JsonNode node, String... keys) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return NullNode.getInstance();
    }
    for (String key : keys) {
      JsonNode value = node.path(key);
      if (!value.isMissingNode()) {
        return value;
      }
    }
    return NullNode.getInstance();
  }

  private static int integer(JsonNode node, int fallback) {
    return node.isIntegralNumber() ? node.asInt() : fallback;
  }
}
