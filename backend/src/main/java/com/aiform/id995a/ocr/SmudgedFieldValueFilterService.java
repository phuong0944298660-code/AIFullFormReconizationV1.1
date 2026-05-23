package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SmudgedFieldValueFilterService {

  private static final Set<String> EXCLUDED_STATUSES = Set.of(
      "smudge",
      "smudged",
      "scribble",
      "scribbled",
      "crossedout",
      "crossed_out",
      "crossed-out",
      "strikethrough",
      "strike_through",
      "strike-through",
      "correction",
      "corrected",
      "erased",
      "erase",
      "deleted",
      "cancelled",
      "canceled",
      "void"
  );

  private final ObjectMapper objectMapper;

  public SmudgedFieldValueFilterService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public SmudgedFieldValueFilterResult filter(JsonNode structuredData) {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    int filtered = 0;

    List<String> pageKeys = pageKeys(mutableData);
    for (String pageKey : pageKeys) {
      JsonNode pageData = mutableData.path(pageKey);
      JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
      List<FieldCandidate> candidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), candidates);
      for (FieldCandidate candidate : candidates) {
        String originalValue = valueText(candidate.value());
        if (originalValue.isBlank()) {
          continue;
        }
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        if (alreadyFilteredByFieldCrop(evidence)) {
          continue;
        }
        FilteredValue filteredValue = filterValue(originalValue, evidence);
        FilteredValue contractFilteredValue = filterLeadingRejectedContractMark(filteredValue.value(), candidate.path(), evidence);
        if (!filteredValue.changed() && !contractFilteredValue.changed()) {
          continue;
        }
        setValue(mutableData, pageKey, candidate.path(), contractFilteredValue.value());
        annotateEvidence(mutableData, pageKey, candidate.path(), evidence, originalValue, contractFilteredValue.value());
        if (contractFilteredValue.changed()) {
          annotateLeadingRejectedContractMark(mutableData, pageKey, candidate.path(), evidence, filteredValue.value(), contractFilteredValue.value());
        }
        filtered += 1;
      }
    }

    return new SmudgedFieldValueFilterResult(mutableData, filtered);
  }

  private boolean alreadyFilteredByFieldCrop(JsonNode evidence) {
    return evidence != null
        && !evidence.isMissingNode()
        && !evidence.isNull()
        && evidence.path("visual_smudge_filter_applied").asBoolean(false);
  }

  private FilteredValue filterValue(String value, JsonNode evidence) {
    if (wholeFieldExcluded(evidence)) {
      return new FilteredValue(true, "");
    }

    List<String> characters = splitCodePoints(value);
    Set<Integer> excludedIndexes = excludedCharacterIndexes(evidence, characters.size());
    String filtered = removeIndexes(characters, excludedIndexes);
    String afterExcludedMarks = removeExcludedMarkText(filtered, evidence);
    return new FilteredValue(!afterExcludedMarks.equals(value), afterExcludedMarks);
  }

  private FilteredValue filterLeadingRejectedContractMark(String value, List<String> path, JsonNode evidence) {
    String compact = value == null ? "" : value.replaceAll("\\s+", "").trim();
    if (compact.length() < 10 || !isContractNumberField(path, evidence, compact)) {
      return new FilteredValue(false, value);
    }
    String upper = compact.toUpperCase(Locale.ROOT);
    if (upper.startsWith("FH-CON-")) {
      return new FilteredValue(false, value);
    }
    int index = upper.indexOf("FH-CON-");
    if (index > 0
        && index <= 2
        && upper.substring(index).matches("^FH-CON-[A-Z]{2,3}\\d{2,4}-\\d{3,}$")) {
      return new FilteredValue(true, compact.substring(index));
    }
    return new FilteredValue(false, value);
  }

  private boolean isContractNumberField(List<String> path, JsonNode evidence, String value) {
    String pathText = String.join(" ", path).toLowerCase(Locale.ROOT);
    String labelText = evidence == null || evidence.isMissingNode() || evidence.isNull()
        ? ""
        : evidence.path("label").asText("").toLowerCase(Locale.ROOT);
    String valueText = value == null ? "" : value.toLowerCase(Locale.ROOT);
    String text = pathText + " " + labelText + " " + valueText;
    return (text.contains("contract") || text.contains("\u5408\u540c"))
        && text.contains("h-con-");
  }

  private Set<Integer> excludedCharacterIndexes(JsonNode evidence, int valueLength) {
    Set<Integer> indexes = new HashSet<>();
    JsonNode charNodes = firstExisting(evidence, "char_confidences", "characters", "chars");
    if (charNodes.isArray()) {
      for (int position = 0; position < charNodes.size(); position += 1) {
        JsonNode node = charNodes.get(position);
        if (!isExcludedStatus(statusOrReason(node))) {
          continue;
        }
        int index = integer(firstExisting(node, "index", "value_index"), position);
        addIndex(indexes, index, valueLength);
      }
    }

    JsonNode excludedMarks = firstExisting(evidence, "excluded_marks", "excluded_mark", "ignored_marks");
    if (excludedMarks.isArray()) {
      for (JsonNode mark : excludedMarks) {
        int index = integer(firstExisting(mark, "start_index", "index", "value_index"), -1);
        int length = Math.max(1, integer(firstExisting(mark, "length", "char_count"), 1));
        if (index < 0) {
          continue;
        }
        for (int offset = 0; offset < length; offset += 1) {
          addIndex(indexes, index + offset, valueLength);
        }
      }
    }
    return indexes;
  }

  private String removeExcludedMarkText(String value, JsonNode evidence) {
    JsonNode excludedMarks = firstExisting(evidence, "excluded_marks", "excluded_mark", "ignored_marks");
    if (!excludedMarks.isArray()) {
      return value;
    }
    String filtered = value;
    for (JsonNode mark : excludedMarks) {
      String text = firstExisting(mark, "text", "char", "value").asText("");
      if (text.isBlank()) {
        continue;
      }
      if (countOccurrences(filtered, text) == 1) {
        filtered = filtered.replace(text, "");
      }
    }
    return filtered;
  }

  private boolean wholeFieldExcluded(JsonNode evidence) {
    if (evidence == null || evidence.isMissingNode() || evidence.isNull()) {
      return false;
    }
    for (String key : List.of(
        "only_smudge",
        "smudge_only",
        "only_scribble",
        "only_correction",
        "only_crossed_out",
        "crossed_out",
        "erased",
        "invalid_due_to_smudge"
    )) {
      if (evidence.path(key).asBoolean(false)) {
        return true;
      }
    }
    return isExcludedStatus(statusOrReason(evidence));
  }

  private String removeIndexes(List<String> characters, Set<Integer> excludedIndexes) {
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

  private void addIndex(Set<Integer> indexes, int index, int valueLength) {
    if (index >= 0 && index < valueLength) {
      indexes.add(index);
    }
  }

  private int countOccurrences(String value, String needle) {
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

  private String statusOrReason(JsonNode node) {
    String status = firstExisting(node, "status", "state").asText("");
    if (!status.isBlank()) {
      return status;
    }
    return firstExisting(node, "reason", "mark_type", "type").asText("");
  }

  private boolean isExcludedStatus(String value) {
    String normalized = normalizeStatus(value);
    return !normalized.isBlank() && EXCLUDED_STATUSES.contains(normalized);
  }

  private String normalizeStatus(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
  }

  private List<String> pageKeys(ObjectNode root) {
    List<String> keys = new ArrayList<>();
    root.fieldNames().forEachRemaining(key -> {
      if (key.matches("page_\\d+")) {
        keys.add(key);
      }
    });
    return keys;
  }

  private void collectCandidates(JsonNode node, List<String> path, List<FieldCandidate> candidates) {
    if (node == null || node.isMissingNode()) {
      return;
    }
    if (isLeafValue(node)) {
      candidates.add(new FieldCandidate(path, node));
      return;
    }
    if (node.isArray()) {
      for (int index = 0; index < node.size(); index += 1) {
        collectCandidates(node.get(index), append(path, String.valueOf(index + 1)), candidates);
      }
      return;
    }
    if (node.isObject()) {
      if (isMetadataLeaf(node)) {
        JsonNode value = node.has("value") ? node.path("value") : node.path("text");
        candidates.add(new FieldCandidate(path, value));
        return;
      }
      node.fields().forEachRemaining(entry -> {
        if (!entry.getKey().startsWith("_") && !isControlField(entry.getKey())) {
          collectCandidates(entry.getValue(), append(path, entry.getKey()), candidates);
        }
      });
    }
  }

  private JsonNode metadataPage(JsonNode structuredData, String key, String pageKey) {
    if (structuredData == null || !structuredData.has(key)) {
      return NullNode.getInstance();
    }
    JsonNode metadata = structuredData.path(key);
    return metadata.path(pageKey).isMissingNode() ? NullNode.getInstance() : metadata.path(pageKey);
  }

  private JsonNode lookupMetadata(JsonNode root, List<String> path, String pageKey) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      return NullNode.getInstance();
    }
    String dottedPath = String.join(".", path);
    if (!pageKey.isBlank()) {
      JsonNode pagePrefixed = root.path(pageKey + "." + dottedPath);
      if (!pagePrefixed.isMissingNode()) {
        return pagePrefixed;
      }
    }
    JsonNode direct = root.path(dottedPath);
    if (!direct.isMissingNode()) {
      return direct;
    }
    JsonNode current = root;
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return NullNode.getInstance();
      }
    }
    return current;
  }

  private void setValue(ObjectNode root, String pageKey, List<String> path, String value) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (path.isEmpty()) {
      return;
    }
    String fieldName = path.get(path.size() - 1);
    if (value.isBlank()) {
      current.putNull(fieldName);
    } else {
      current.put(fieldName, value);
    }
  }

  private void annotateEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      String originalValue,
      String filteredValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("smudge_filter_applied", true);
    evidence.put("original_value", originalValue);
    if (filteredValue.isBlank()) {
      evidence.putNull("filtered_value");
    } else {
      evidence.put("filtered_value", filteredValue);
    }
  }

  private void annotateLeadingRejectedContractMark(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      String originalValue,
      String filteredValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("leading_rejected_contract_mark_filtered", true);
    evidence.put("leading_rejected_contract_mark_original_value", originalValue);
    evidence.put("leading_rejected_contract_mark_filtered_value", filteredValue);
  }

  private ObjectNode evidenceNode(ObjectNode root, String pageKey, List<String> path) {
    ObjectNode evidenceRoot = objectChild(root, "_field_evidence");
    ObjectNode pageEvidence = objectChild(evidenceRoot, pageKey);
    ObjectNode evidence = pageEvidence;
    for (String part : path) {
      evidence = objectChild(evidence, part);
    }
    return evidence;
  }

  private ObjectNode objectChild(ObjectNode parent, String fieldName) {
    JsonNode existing = parent.get(fieldName);
    if (existing instanceof ObjectNode objectNode) {
      return objectNode;
    }
    ObjectNode child = objectMapper.createObjectNode();
    parent.set(fieldName, child);
    return child;
  }

  private JsonNode firstExisting(JsonNode node, String... keys) {
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

  private int integer(JsonNode node, int fallback) {
    return node.isIntegralNumber() ? node.asInt() : fallback;
  }

  private boolean isLeafValue(JsonNode node) {
    return node.isNull() || node.isTextual() || node.isBoolean() || node.isNumber();
  }

  private boolean isMetadataLeaf(JsonNode node) {
    return node != null
        && node.isObject()
        && (node.has("value") || node.has("text"))
        && (node.has("confidence") || node.size() <= 4);
  }

  private boolean isControlField(String key) {
    String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[_\\s-]+", "");
    return "noapplicantinput".equals(normalized);
  }

  private String valueText(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode() || value.isBoolean()) {
      return "";
    }
    return value.asText("");
  }

  private List<String> splitCodePoints(String text) {
    return text.codePoints()
        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
        .toList();
  }

  private List<String> append(List<String> path, String key) {
    List<String> next = new ArrayList<>(path);
    next.add(key);
    return List.copyOf(next);
  }

  private record FieldCandidate(List<String> path, JsonNode value) {}

  private record FilteredValue(boolean changed, String value) {}
}
