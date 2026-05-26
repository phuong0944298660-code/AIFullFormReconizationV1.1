package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AddressFieldCropRefinementService {

  private static final double MIN_REPLACEMENT_CONFIDENCE = 85;
  private static final String WIDE_ADDRESS_REVIEW_SUFFIX = ".__address_wide_crop";

  private final FieldCropTranscriptionGateway transcriptionGateway;
  private final ObjectMapper objectMapper;

  public AddressFieldCropRefinementService(
      FieldCropTranscriptionGateway transcriptionGateway,
      ObjectMapper objectMapper
  ) {
    this.transcriptionGateway = transcriptionGateway;
    this.objectMapper = objectMapper;
  }

  public AddressFieldCropRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile
  ) throws IOException {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    List<FieldCropTranscriptionRequest> requests = new ArrayList<>();
    Map<String, AddressCandidate> candidatesByKey = new LinkedHashMap<>();

    for (RenderedOcrPage page : pages == null ? List.<RenderedOcrPage>of() : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = mutableData.path(pageKey);
      JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
      List<FieldCandidate> fieldCandidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), fieldCandidates);
      for (FieldCandidate candidate : fieldCandidates) {
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        String value = valueText(candidate.value());
        if (!isAddressCandidate(candidate.path(), label, value)) {
          continue;
        }
        List<Integer> bbox = parseBbox(evidence, page.imageWidth(), page.imageHeight());
        CropResult crop = crop(page, bbox);
        if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
          continue;
        }
        String path = String.join(".", candidate.path());
        AddressCandidate addressCandidate = new AddressCandidate(page.page(), candidate.path(), path, label, value, evidence);
        candidatesByKey.put(key(page.page(), path), addressCandidate);
        requests.add(new FieldCropTranscriptionRequest(
            page.page(),
            path,
            label,
            value,
            crop.bytes(),
            crop.dataUrl()
        ));
        addWideAddressReviewRequest(page, path, label, value, bbox, addressCandidate, requests, candidatesByKey);
      }
    }

    if (requests.isEmpty()) {
      return new AddressFieldCropRefinementResult(mutableData, 0, 0);
    }

    List<FieldCropTranscriptionResult> results;
    try {
      results = transcriptionGateway.transcribeFieldCrops(filename, requests, modelProfile);
    } catch (IOException exception) {
      return new AddressFieldCropRefinementResult(mutableData, requests.size(), 0);
    }

    int updated = 0;
    for (FieldCropTranscriptionResult result : results == null ? List.<FieldCropTranscriptionResult>of() : results) {
      AddressCandidate candidate = candidatesByKey.get(key(result.page(), result.path()));
      if (candidate == null) {
        continue;
      }
      String pageKey = "page_" + candidate.page();
      String currentValue = currentTextValue(mutableData, pageKey, candidate.path(), candidate.currentValue());
      String filteredCropText = result.textWithoutExcludedMarks();
      boolean shouldApplyTranscription = shouldApply(currentValue, result, filteredCropText);
      boolean hasRejectedMarks = hasExcludedMarks(result);
      if (shouldApplyTranscription) {
        setValue(mutableData, pageKey, candidate.path(), filteredCropText);
        setConfidence(mutableData, pageKey, candidate.path(), result.confidence());
        setSecondaryEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result);
        updated += 1;
        continue;
      }
      if (hasRejectedMarks) {
        setSecondaryEvidence(mutableData, pageKey, candidate.path(), candidate.evidence(), result);
        updated += 1;
      }
    }

    return new AddressFieldCropRefinementResult(mutableData, requests.size(), updated);
  }

  private void addWideAddressReviewRequest(
      RenderedOcrPage page,
      String path,
      String label,
      String value,
      List<Integer> bbox,
      AddressCandidate candidate,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, AddressCandidate> candidatesByKey
  ) {
    if (!needsWideAddressReview(value, bbox, page)) {
      return;
    }
    CropResult crop = crop(page, bbox, FieldCropper.CropKind.ADDRESS_WIDE);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String reviewPath = path + WIDE_ADDRESS_REVIEW_SUFFIX;
    AddressCandidate wideCandidate = new AddressCandidate(
        candidate.page(),
        candidate.path(),
        candidate.dottedPath(),
        candidate.label(),
        candidate.currentValue(),
        candidate.evidence()
    );
    candidatesByKey.put(key(page.page(), reviewPath), wideCandidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        reviewPath,
        label + " wide address field crop; read every visible applicant-filled address line top-to-bottom, including No digits and lower building/floor/room lines exactly",
        value,
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private boolean needsWideAddressReview(String value, List<Integer> bbox, RenderedOcrPage page) {
    if (bbox == null || bbox.size() < 4 || page == null || page.imageHeight() <= 0) {
      return false;
    }
    int height = Math.max(1, bbox.get(3) - bbox.get(1));
    if (height <= Math.round(page.imageHeight() * 0.050f)) {
      return true;
    }
    if (page.imageHeight() > 800 && height <= 80) {
      return true;
    }
    String compact = compactValue(value).toLowerCase(Locale.ROOT);
    return compact.contains("no") || compact.contains("n0");
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

  private boolean isAddressCandidate(List<String> path, String label, String value) {
    String joinedPath = String.join(" ", path);
    String text = (joinedPath + " " + label + " " + value).toLowerCase(Locale.ROOT);
    if (text.contains("email") || text.contains("e-mail") || text.contains("mail address") || text.contains("\u96fb\u90f5")) {
      return false;
    }
    return text.contains("address")
        || text.contains("住址")
        || text.contains("地址")
        || text.contains("通訊")
        || text.contains("通信")
        || text.contains("correspondence")
        || text.contains("residential")
        || text.contains("室")
        || text.contains("樓")
        || text.contains("座");
  }

  private boolean shouldApply(String currentValue, FieldCropTranscriptionResult result, String filteredCropText) {
    if (!isUsableTranscription(result)) {
      return false;
    }
    String text = filteredCropText == null ? "" : filteredCropText.trim();
    String current = currentValue == null ? "" : currentValue.trim();
    if (looksLikeLossyCropCandidate(current, text)) {
      return false;
    }
    return !text.equals(current);
  }

  private boolean looksLikeLossyCropCandidate(String currentValue, String cropText) {
    String current = compactValue(currentValue);
    String crop = compactValue(cropText);
    if (current.isBlank() || crop.isBlank() || crop.length() >= current.length()) {
      return false;
    }
    int missing = current.length() - crop.length();
    if (missing > Math.max(4, Math.ceil(current.length() * 0.25))) {
      return true;
    }
    return current.contains(crop) || isSubsequence(crop, current);
  }

  private String compactValue(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").trim();
  }

  private boolean isSubsequence(String shorter, String longer) {
    int index = 0;
    for (int i = 0; i < longer.length() && index < shorter.length(); i += 1) {
      if (shorter.charAt(index) == longer.charAt(i)) {
        index += 1;
      }
    }
    return index == shorter.length();
  }

  private boolean isUsableTranscription(FieldCropTranscriptionResult result) {
    String text = result.textWithoutExcludedMarks();
    if (text.isBlank() || result.confidence() < MIN_REPLACEMENT_CONFIDENCE) {
      return false;
    }
    String status = result.status().toLowerCase(Locale.ROOT);
    return status.equals("ok") || status.equals("available") || status.equals("refined");
  }

  private boolean hasExcludedMarks(FieldCropTranscriptionResult result) {
    return result.excludedMarks().isArray() && !result.excludedMarks().isEmpty();
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

  private String label(JsonNode evidence, List<String> path) {
    String label = firstExisting(evidence, "label", "field_label", "name").asText("");
    return label.isBlank() ? humanize(path.isEmpty() ? "field" : path.get(path.size() - 1)) : label;
  }

  private String valueText(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode() || value.isBoolean()) {
      return "";
    }
    return value.asText("");
  }

  private List<Integer> parseBbox(JsonNode evidence, int imageWidth, int imageHeight) {
    JsonNode bbox = firstExisting(evidence, "bbox", "value_bbox", "field_bbox", "region", "box");
    if (bbox.isMissingNode() || bbox.isNull()) {
      return List.of();
    }
    if (bbox.isObject()) {
      double x = number(firstExisting(bbox, "x", "left"));
      double y = number(firstExisting(bbox, "y", "top"));
      double width = number(firstExisting(bbox, "width", "w"));
      double height = number(firstExisting(bbox, "height", "h"));
      return rectToAbsolute(x, y, width, height, imageWidth, imageHeight);
    }
    if (bbox.isArray() && bbox.size() >= 4) {
      double a = bbox.get(0).asDouble();
      double b = bbox.get(1).asDouble();
      double c = bbox.get(2).asDouble();
      double d = bbox.get(3).asDouble();
      if (a <= 1 && b <= 1 && c <= 1 && d <= 1) {
        return rectToAbsolute(a, b, c, d, imageWidth, imageHeight);
      }
      if (looksLikeWidthHeight(a, b, c, d, imageWidth, imageHeight)) {
        return rectToAbsolute(a, b, c, d, imageWidth, imageHeight);
      }
      return clampBbox(List.of((int) Math.round(a), (int) Math.round(b), (int) Math.round(c), (int) Math.round(d)), imageWidth, imageHeight);
    }
    return List.of();
  }

  private List<Integer> rectToAbsolute(double x, double y, double width, double height, int imageWidth, int imageHeight) {
    boolean normalized = x <= 1 && y <= 1 && width <= 1 && height <= 1;
    int left = (int) Math.round(normalized ? x * imageWidth : x);
    int top = (int) Math.round(normalized ? y * imageHeight : y);
    int right = (int) Math.round(left + (normalized ? width * imageWidth : width));
    int bottom = (int) Math.round(top + (normalized ? height * imageHeight : height));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private boolean looksLikeWidthHeight(double x, double y, double width, double height, int imageWidth, int imageHeight) {
    if (width <= 0 || height <= 0) {
      return false;
    }
    if (width <= x || height <= y) {
      return true;
    }
    return width <= imageWidth - x
        && height <= imageHeight - y
        && (width <= imageWidth * 0.8 || height <= imageHeight * 0.25);
  }

  private List<Integer> clampBbox(List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    int left = Math.max(0, Math.min(imageWidth, bbox.get(0)));
    int top = Math.max(0, Math.min(imageHeight, bbox.get(1)));
    int right = Math.max(0, Math.min(imageWidth, bbox.get(2)));
    int bottom = Math.max(0, Math.min(imageHeight, bbox.get(3)));
    if (right <= left || bottom <= top) {
      return List.of();
    }
    return List.of(left, top, right, bottom);
  }

  private CropResult crop(RenderedOcrPage page, List<Integer> bbox) {
    return crop(page, bbox, FieldCropper.CropKind.ADDRESS);
  }

  private CropResult crop(RenderedOcrPage page, List<Integer> bbox, FieldCropper.CropKind kind) {
    FieldCropper.CropResult crop = FieldCropper.crop(page, bbox, kind);
    return new CropResult(crop.bytes(), crop.dataUrl());
  }

  private void setValue(ObjectNode root, String pageKey, List<String> path, String value) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (!path.isEmpty()) {
      current.put(path.get(path.size() - 1), value);
    }
  }

  private String currentTextValue(ObjectNode root, String pageKey, List<String> path, String fallback) {
    JsonNode current = root.path(pageKey);
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return fallback == null ? "" : fallback;
      }
    }
    if (current.isTextual() || current.isNumber()) {
      return current.asText("");
    }
    return fallback == null ? "" : fallback;
  }

  private void setConfidence(ObjectNode root, String pageKey, List<String> path, double confidence) {
    ObjectNode confidenceRoot = objectChild(root, "_confidence");
    ObjectNode pageConfidence = objectChild(confidenceRoot, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      pageConfidence = objectChild(pageConfidence, path.get(index));
    }
    if (!path.isEmpty()) {
      pageConfidence.put(path.get(path.size() - 1), Math.round(confidence));
    }
  }

  private void setSecondaryEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result
  ) {
    if (existingEvidence instanceof ObjectNode evidenceObject) {
      putSecondaryEvidence(evidenceObject, result);
      return;
    }
    ObjectNode evidenceRoot = objectChild(root, "_field_evidence");
    ObjectNode pageEvidence = objectChild(evidenceRoot, pageKey);
    ObjectNode evidence = pageEvidence;
    for (int index = 0; index < path.size(); index += 1) {
      evidence = objectChild(evidence, path.get(index));
    }
    putSecondaryEvidence(evidence, result);
  }

  private void putSecondaryEvidence(
      ObjectNode evidence,
      FieldCropTranscriptionResult result
  ) {
    evidence.put("secondary_transcription_text", result.text());
    if (hasExcludedMarks(result)) {
      evidence.put("secondary_transcription_text_after_excluded_marks", result.textWithoutExcludedMarks());
    }
    evidence.put("secondary_transcription_confidence", Math.round(result.confidence()));
    evidence.put("secondary_transcription_status", result.status());
    if (!result.addressNumberFragment().isBlank()) {
      evidence.put("address_number_fragment", result.addressNumberFragment());
    }
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.set("secondary_excluded_marks", result.excludedMarks().deepCopy());
      mergeExcludedMarks(evidence, result.excludedMarks());
    }
  }

  private void mergeExcludedMarks(ObjectNode evidence, JsonNode excludedMarks) {
    JsonNode existing = evidence.path("excluded_marks");
    if (!existing.isArray() || existing.isEmpty()) {
      evidence.set("excluded_marks", excludedMarks.deepCopy());
      return;
    }
    ArrayNode merged = objectMapper.createArrayNode();
    existing.forEach(item -> merged.add(item.deepCopy()));
    excludedMarks.forEach(item -> merged.add(item.deepCopy()));
    evidence.set("excluded_marks", merged);
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

  private double number(JsonNode node) {
    return node.isNumber() ? node.asDouble() : 0;
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

  private String humanize(String key) {
    return key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private List<String> append(List<String> path, String key) {
    List<String> next = new ArrayList<>(path);
    next.add(key);
    return List.copyOf(next);
  }

  private String key(int page, String path) {
    return page + ":" + path;
  }

  private record FieldCandidate(List<String> path, JsonNode value) {}

  private record AddressCandidate(
      int page,
      List<String> path,
      String dottedPath,
      String label,
      String currentValue,
      JsonNode evidence
  ) {}

  private record CropResult(byte[] bytes, String dataUrl) {}

}
