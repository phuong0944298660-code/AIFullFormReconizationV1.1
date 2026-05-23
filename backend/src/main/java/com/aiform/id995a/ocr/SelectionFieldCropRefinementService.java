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
public class SelectionFieldCropRefinementService {

  private static final double MIN_BLANK_CONFIDENCE = 70;
  private static final double MIN_REPLACEMENT_CONFIDENCE = 85;

  private final FieldCropTranscriptionGateway transcriptionGateway;
  private final ObjectMapper objectMapper;

  public SelectionFieldCropRefinementService(
      FieldCropTranscriptionGateway transcriptionGateway,
      ObjectMapper objectMapper
  ) {
    this.transcriptionGateway = transcriptionGateway;
    this.objectMapper = objectMapper;
  }

  public SelectionFieldCropRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile
  ) throws IOException {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    List<FieldCropTranscriptionRequest> requests = new ArrayList<>();
    Map<String, SelectionCandidate> candidatesByKey = new LinkedHashMap<>();

    for (RenderedOcrPage page : pages == null ? List.<RenderedOcrPage>of() : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = mutableData.path(pageKey);
      JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
      List<FieldCandidate> fieldCandidates = new ArrayList<>();
      collectCandidates(pageData, List.of(), fieldCandidates);
      for (FieldCandidate candidate : fieldCandidates) {
        String value = valueText(candidate.value());
        JsonNode evidence = lookupMetadata(evidenceData, candidate.path(), pageKey);
        String label = label(evidence, candidate.path());
        if (!isSelectionCandidate(candidate.path(), label, value)) {
          continue;
        }
        List<Integer> bbox = parseBbox(evidence, page.imageWidth(), page.imageHeight());
        CropResult crop = crop(page, bbox);
        if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
          continue;
        }
        String path = String.join(".", candidate.path());
        SelectionCandidate selectionCandidate = new SelectionCandidate(page.page(), candidate.path(), path, label, value, evidence);
        candidatesByKey.put(key(page.page(), path), selectionCandidate);
        requests.add(new FieldCropTranscriptionRequest(
            page.page(),
            path,
            label,
            value,
            crop.bytes(),
            crop.dataUrl()
        ));
      }
      addMissingSeparateServantRoomCandidate(page, pageData, mutableData, requests, candidatesByKey);
      addMissingHouseholdIncomeDeclarationCandidate(page, pageData, mutableData, requests, candidatesByKey);
      addMissingHkIdentityCardNoCandidate(page, pageData, mutableData, requests, candidatesByKey);
    }

    if (requests.isEmpty()) {
      return new SelectionFieldCropRefinementResult(mutableData, 0, 0);
    }

    List<FieldCropTranscriptionResult> results;
    try {
      results = transcriptionGateway.transcribeFieldCrops(filename, requests, modelProfile);
    } catch (IOException exception) {
      return new SelectionFieldCropRefinementResult(mutableData, requests.size(), 0);
    }

    int updated = 0;
    for (FieldCropTranscriptionResult result : results == null ? List.<FieldCropTranscriptionResult>of() : results) {
      SelectionCandidate candidate = candidatesByKey.get(key(result.page(), result.path()));
      if (candidate == null) {
        continue;
      }
      String filteredCropText = result.textWithoutExcludedMarks();
      if (shouldClear(result, filteredCropText)) {
        removeValue(mutableData, "page_" + candidate.page(), candidate.path());
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setSelectionEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate.currentValue(), "");
        updated += 1;
        continue;
      }
      if (shouldReplace(candidate.currentValue(), result, filteredCropText)) {
        String nextValue = filteredCropText;
        setValue(mutableData, "page_" + candidate.page(), candidate.path(), nextValue);
        setConfidence(mutableData, "page_" + candidate.page(), candidate.path(), result.confidence());
        setSelectionEvidence(mutableData, "page_" + candidate.page(), candidate.path(), candidate.evidence(), result, candidate.currentValue(), nextValue);
        updated += 1;
      }
    }

    return new SelectionFieldCropRefinementResult(mutableData, requests.size(), updated);
  }

  private boolean shouldClear(FieldCropTranscriptionResult result, String filteredCropText) {
    String status = normalizeStatus(result.status());
    boolean blankStatus = List.of(
        "blank",
        "unchecked",
        "not_selected",
        "notselected",
        "not_checked",
        "notchecked",
        "smudged",
        "smudge",
        "scribble",
        "crossed_out",
        "crossedout",
        "correction",
        "erased"
    ).contains(status);
    boolean rejectedMarks = result.excludedMarks().isArray() && !result.excludedMarks().isEmpty();
    return result.confidence() >= MIN_BLANK_CONFIDENCE
        && filteredCropText.trim().isBlank()
        && (blankStatus || rejectedMarks);
  }

  private boolean shouldReplace(String currentValue, FieldCropTranscriptionResult result, String filteredCropText) {
    String text = filteredCropText == null ? "" : filteredCropText.trim();
    if (text.isBlank() || result.confidence() < MIN_REPLACEMENT_CONFIDENCE) {
      return false;
    }
    String status = normalizeStatus(result.status());
    if (!(status.equals("ok") || status.equals("available") || status.equals("refined"))) {
      return false;
    }
    String current = currentValue == null ? "" : currentValue.trim();
    return !text.equals(current);
  }

  private void addMissingSeparateServantRoomCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("separate_servant_room");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode bedroomEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        List.of("number_of_bedroom"),
        List.of("number_of_bedrooms"),
        List.of("number_of_bedroom(s)")
    );
    List<Integer> bedroomBbox = parseBbox(bedroomEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> servantRoomBbox = deriveRightSideOptionGroupBbox(bedroomBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, servantRoomBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "Separate servant room / 獨立工人房 / 独立工人房 (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", servantRoomBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private void addMissingHouseholdIncomeDeclarationCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("average_monthly_household_income_no_less_than_hk15000");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode incomeEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        List.of("average_monthly_household_income_no_less_than"),
        List.of("average_monthly_household_income_no_less_than_hk"),
        List.of("monthly_household_income_no_less_than"),
        List.of("household_income_no_less_than")
    );
    List<Integer> incomeBbox = parseBbox(incomeEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> declarationBbox = deriveLeftSideOptionGroupBbox(incomeBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, declarationBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "Average monthly household income no less than HK$15,000 declaration (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", declarationBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  private void addMissingHkIdentityCardNoCandidate(
      RenderedOcrPage page,
      JsonNode pageData,
      ObjectNode mutableData,
      List<FieldCropTranscriptionRequest> requests,
      Map<String, SelectionCandidate> candidatesByKey
  ) {
    List<String> path = List.of("hk_identity_card_no");
    if (hasFilledValue(pageData, path)) {
      return;
    }
    String pageKey = "page_" + page.page();
    JsonNode evidenceData = metadataPage(mutableData, "_field_evidence", pageKey);
    JsonNode hkEvidence = firstAvailableEvidence(
        evidenceData,
        pageKey,
        path,
        List.of("hong_kong_identity_card_no"),
        List.of("hk_id_card_no")
    );
    List<Integer> hkBbox = parseBbox(hkEvidence, page.imageWidth(), page.imageHeight());
    List<Integer> optionBbox = hkBbox.isEmpty()
        ? deriveLeftSideOptionGroupBbox(
            parseBbox(firstAvailableEvidence(evidenceData, pageKey, List.of("nationality"), List.of("occupation")), page.imageWidth(), page.imageHeight()),
            page.imageWidth(),
            page.imageHeight()
        )
        : expandOptionGroupBbox(hkBbox, page.imageWidth(), page.imageHeight());
    CropResult crop = crop(page, optionBbox);
    if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
      return;
    }
    String label = "HK identity card no. / 香港身份證號碼 / 香港身份证号码 (Yes/No)";
    ObjectNode evidence = evidenceNode(mutableData, pageKey, path);
    evidence.put("label", label);
    putNormalizedBbox(evidence, "value_bbox", optionBbox, page.imageWidth(), page.imageHeight());
    SelectionCandidate candidate = new SelectionCandidate(page.page(), path, String.join(".", path), label, "", evidence);
    candidatesByKey.put(key(page.page(), candidate.dottedPath()), candidate);
    requests.add(new FieldCropTranscriptionRequest(
        page.page(),
        candidate.dottedPath(),
        label,
        "",
        crop.bytes(),
        crop.dataUrl()
    ));
  }

  @SafeVarargs
  private final JsonNode firstAvailableEvidence(JsonNode evidenceData, String pageKey, List<String>... paths) {
    for (List<String> path : paths) {
      JsonNode evidence = lookupMetadata(evidenceData, path, pageKey);
      if (!evidence.isMissingNode() && !evidence.isNull()) {
        return evidence;
      }
    }
    return NullNode.getInstance();
  }

  private List<Integer> deriveRightSideOptionGroupBbox(List<Integer> anchorBbox, int imageWidth, int imageHeight) {
    if (anchorBbox.size() < 4) {
      return List.of();
    }
    int anchorHeight = Math.max(1, anchorBbox.get(3) - anchorBbox.get(1));
    int left = Math.min(imageWidth, anchorBbox.get(2) + Math.max(8, Math.round(imageWidth * 0.05f)));
    int right = Math.min(imageWidth, anchorBbox.get(2) + Math.max(80, Math.round(imageWidth * 0.55f)));
    int top = Math.max(0, anchorBbox.get(1) - Math.max(8, Math.round(anchorHeight * 1.35f)));
    int bottom = Math.min(imageHeight, anchorBbox.get(3) + Math.max(8, Math.round(anchorHeight * 1.35f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private List<Integer> deriveLeftSideOptionGroupBbox(List<Integer> anchorBbox, int imageWidth, int imageHeight) {
    if (anchorBbox.size() < 4) {
      return List.of();
    }
    int anchorHeight = Math.max(1, anchorBbox.get(3) - anchorBbox.get(1));
    int left = Math.max(0, anchorBbox.get(0) - Math.max(90, Math.round(imageWidth * 0.48f)));
    int right = Math.max(left + 1, anchorBbox.get(0) - Math.max(12, Math.round(imageWidth * 0.06f)));
    int top = Math.max(0, anchorBbox.get(1) - Math.max(8, Math.round(anchorHeight * 1.25f)));
    int bottom = Math.min(imageHeight, anchorBbox.get(3) + Math.max(8, Math.round(anchorHeight * 1.25f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private List<Integer> expandOptionGroupBbox(List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4) {
      return List.of();
    }
    int height = Math.max(1, bbox.get(3) - bbox.get(1));
    int left = Math.max(0, bbox.get(0) - Math.max(18, Math.round(imageWidth * 0.06f)));
    int right = Math.min(imageWidth, bbox.get(2) + Math.max(24, Math.round(imageWidth * 0.08f)));
    int top = Math.max(0, bbox.get(1) - Math.max(8, Math.round(height * 1.25f)));
    int bottom = Math.min(imageHeight, bbox.get(3) + Math.max(8, Math.round(height * 1.25f)));
    return clampBbox(List.of(left, top, right, bottom), imageWidth, imageHeight);
  }

  private boolean hasFilledValue(JsonNode pageData, List<String> path) {
    JsonNode current = pageData;
    for (String part : path) {
      current = current.path(part);
      if (current.isMissingNode()) {
        return false;
      }
    }
    if (current.isNull()) {
      return false;
    }
    if (current.isTextual()) {
      return !current.asText().trim().isBlank();
    }
    return !current.isMissingNode();
  }

  private boolean isSelectionCandidate(List<String> path, String label, String value) {
    String joinedPath = String.join(" ", path);
    String text = (joinedPath + " " + label + " " + value).toLowerCase(Locale.ROOT);
    if (looksLikeOrdinaryValue(path, label, value)) {
      return false;
    }
    boolean selectionKeywords = text.contains("checkbox")
        || text.contains("checked")
        || text.contains("selected")
        || text.contains("declaration")
        || text.contains("convicted")
        || text.contains("crime")
        || text.contains("offence")
        || text.contains("offense")
        || text.contains("refused")
        || text.contains("deported")
        || text.contains("permit")
        || text.contains("勾选")
        || text.contains("勾選")
        || text.contains("声明")
        || text.contains("聲明")
        || text.contains("拒绝")
        || text.contains("拒絕")
        || text.contains("罪");
    String labelText = label == null ? "" : label.toLowerCase(Locale.ROOT);
    if (selectionKeywords && (labelText.length() >= 36 || labelText.contains("i have"))) {
      return true;
    }
    boolean statementValue = value.trim().length() >= 36
        || value.toLowerCase(Locale.ROOT).contains("i have")
        || value.contains("本人");
    return selectionKeywords && statementValue;
  }

  private boolean looksLikeOrdinaryValue(List<String> path, String label, String value) {
    String joinedPath = String.join(" ", path).toLowerCase(Locale.ROOT);
    String shortLabel = label == null || label.length() > 40 ? "" : label.toLowerCase(Locale.ROOT);
    String text = joinedPath + " " + shortLabel;
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}")) {
      return true;
    }
    return text.equals("date")
        || text.endsWith(" date")
        || text.contains("date_of")
        || text.contains("signature")
        || text.contains("name")
        || text.contains("address")
        || text.contains("telephone")
        || text.contains("phone")
        || text.contains("email")
        || text.contains("e-mail");
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
    FieldCropper.CropResult crop = FieldCropper.crop(page, bbox, FieldCropper.CropKind.SELECTION);
    return new CropResult(crop.bytes(), crop.dataUrl());
  }

  private void setValue(ObjectNode root, String pageKey, List<String> path, String value) {
    ObjectNode current = objectChild(root, pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = objectChild(current, path.get(index));
    }
    if (path.isEmpty()) {
      return;
    }
    if (value == null || value.isBlank()) {
      current.putNull(path.get(path.size() - 1));
    } else {
      current.put(path.get(path.size() - 1), value);
    }
  }

  private void removeValue(ObjectNode root, String pageKey, List<String> path) {
    JsonNode current = root.path(pageKey);
    for (int index = 0; index < path.size() - 1; index += 1) {
      current = current.path(path.get(index));
      if (!current.isObject()) {
        return;
      }
    }
    if (!path.isEmpty() && current instanceof ObjectNode objectNode) {
      objectNode.remove(path.get(path.size() - 1));
    }
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

  private void setSelectionEvidence(
      ObjectNode root,
      String pageKey,
      List<String> path,
      JsonNode existingEvidence,
      FieldCropTranscriptionResult result,
      String originalValue,
      String filteredValue
  ) {
    ObjectNode evidence = existingEvidence instanceof ObjectNode objectNode
        ? objectNode
        : evidenceNode(root, pageKey, path);
    evidence.put("selection_crop_status", result.status());
    evidence.put("selection_crop_confidence", Math.round(result.confidence()));
    evidence.put("selection_crop_text", result.text());
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.put("selection_crop_text_after_excluded_marks", result.textWithoutExcludedMarks());
    }
    evidence.put("selection_filtered_out", filteredValue == null || filteredValue.isBlank());
    evidence.put("original_value", originalValue);
    if (filteredValue == null || filteredValue.isBlank()) {
      evidence.putNull("filtered_value");
    } else {
      evidence.put("filtered_value", filteredValue);
    }
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.set("secondary_excluded_marks", result.excludedMarks().deepCopy());
      mergeExcludedMarks(evidence, result.excludedMarks());
    }
  }

  private void putNormalizedBbox(ObjectNode node, String key, List<Integer> bbox, int imageWidth, int imageHeight) {
    if (bbox.size() < 4 || imageWidth <= 0 || imageHeight <= 0) {
      return;
    }
    ObjectNode value = node.putObject(key);
    value.put("x", round3(bbox.get(0) / (double) imageWidth));
    value.put("y", round3(bbox.get(1) / (double) imageHeight));
    value.put("width", round3((bbox.get(2) - bbox.get(0)) / (double) imageWidth));
    value.put("height", round3((bbox.get(3) - bbox.get(1)) / (double) imageHeight));
  }

  private double round3(double value) {
    return Math.round(value * 1000.0) / 1000.0;
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

  private String normalizeStatus(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
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

  private record SelectionCandidate(
      int page,
      List<String> path,
      String dottedPath,
      String label,
      String currentValue,
      JsonNode evidence
  ) {}

  private record CropResult(byte[] bytes, String dataUrl) {}
}
