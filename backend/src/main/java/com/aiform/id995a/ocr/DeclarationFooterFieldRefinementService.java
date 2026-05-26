package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class DeclarationFooterFieldRefinementService {

  private static final double MIN_CONFIDENCE = 85;
  private static final List<FieldSpec> FOOTER_FIELDS = List.of(
      new FieldSpec(
          "declaration_of_applicant_refused_visa_entry",
          "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.",
          new NormalizedRect(0.02, 0.525, 0.96, 0.095)
      ),
      new FieldSpec("date", "Date", new NormalizedRect(0.32, 0.825, 0.31, 0.120)),
      new FieldSpec("signature_of_applicant", "Signature of applicant", new NormalizedRect(0.49, 0.815, 0.50, 0.135))
  );

  private final FieldCropTranscriptionGateway transcriptionGateway;
  private final ObjectMapper objectMapper;

  public DeclarationFooterFieldRefinementService(
      FieldCropTranscriptionGateway transcriptionGateway,
      ObjectMapper objectMapper
  ) {
    this.transcriptionGateway = transcriptionGateway;
    this.objectMapper = objectMapper;
  }

  public DeclarationFooterFieldRefinementResult refine(
      String filename,
      JsonNode structuredData,
      List<RenderedOcrPage> pages,
      LlmModelProfile modelProfile
  ) throws IOException {
    ObjectNode mutableData = structuredData != null && structuredData.isObject()
        ? structuredData.deepCopy()
        : objectMapper.createObjectNode();
    List<FieldCropTranscriptionRequest> requests = new ArrayList<>();
    for (RenderedOcrPage page : pages == null ? List.<RenderedOcrPage>of() : pages) {
      String pageKey = "page_" + page.page();
      JsonNode pageData = mutableData.path(pageKey);
      if (!looksLikeDeclarationPage(pageData)) {
        continue;
      }
      for (FieldSpec field : FOOTER_FIELDS) {
        if (hasValue(pageData.path(field.path()))) {
          continue;
        }
        CropResult crop = crop(page, field);
        if (crop.bytes().length == 0 || crop.dataUrl().isBlank()) {
          continue;
        }
        requests.add(new FieldCropTranscriptionRequest(
            page.page(),
            field.path(),
            field.label(),
            "",
            crop.bytes(),
            crop.dataUrl()
        ));
      }
    }
    if (requests.isEmpty()) {
      return new DeclarationFooterFieldRefinementResult(mutableData, 0, 0);
    }

    List<FieldCropTranscriptionResult> results;
    try {
      results = transcriptionGateway.transcribeFieldCrops(filename, requests, modelProfile);
    } catch (IOException exception) {
      return new DeclarationFooterFieldRefinementResult(mutableData, requests.size(), 0);
    }

    int updated = 0;
    for (FieldCropTranscriptionResult result : results == null ? List.<FieldCropTranscriptionResult>of() : results) {
      FieldSpec field = fieldSpec(result.path());
      if (field == null || !isUsable(result)) {
        continue;
      }
      String pageKey = "page_" + result.page();
      setValue(mutableData, pageKey, field.path(), result.textWithoutExcludedMarks());
      setConfidence(mutableData, pageKey, field.path(), result.confidence());
      setEvidence(mutableData, pageKey, field, result);
      updated += 1;
    }
    return new DeclarationFooterFieldRefinementResult(mutableData, requests.size(), updated);
  }

  private boolean looksLikeDeclarationPage(JsonNode pageData) {
    if (pageData == null || !pageData.isObject()) {
      return false;
    }
    return pageData.has("declaration_of_applicant_previous_name")
        || pageData.has("declaration_of_applicant_refused_visa_entry")
        || pageData.has("declaration_of_applicant_convicted_crimes")
        || pageData.has("declaration_of_applicant_name_changed_before");
  }

  private boolean hasValue(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) {
      return false;
    }
    return !value.isTextual() || !value.asText("").isBlank();
  }

  private FieldSpec fieldSpec(String path) {
    return FOOTER_FIELDS.stream()
        .filter(field -> field.path().equals(path))
        .findFirst()
        .orElse(null);
  }

  private boolean isUsable(FieldCropTranscriptionResult result) {
    if (result == null || result.textWithoutExcludedMarks().isBlank() || result.confidence() < MIN_CONFIDENCE) {
      return false;
    }
    String status = result.status() == null ? "" : result.status().toLowerCase(Locale.ROOT);
    return status.equals("ok") || status.equals("available") || status.equals("refined");
  }

  private CropResult crop(RenderedOcrPage page, FieldSpec field) {
    if (page == null) {
      return new CropResult(new byte[0], "");
    }
    List<Integer> bbox = rectToAbsolute(field.rect(), page.imageWidth(), page.imageHeight());
    FieldCropper.CropKind kind = field.path().contains("signature")
        ? FieldCropper.CropKind.SIGNATURE
        : FieldCropper.CropKind.LONG_TEXT;
    FieldCropper.CropResult crop = FieldCropper.crop(page, bbox, kind);
    return new CropResult(crop.bytes(), crop.dataUrl());
  }

  private List<Integer> rectToAbsolute(NormalizedRect rect, int imageWidth, int imageHeight) {
    int left = clamp((int) Math.round(rect.x() * imageWidth), 0, imageWidth);
    int top = clamp((int) Math.round(rect.y() * imageHeight), 0, imageHeight);
    int right = clamp((int) Math.round((rect.x() + rect.width()) * imageWidth), 0, imageWidth);
    int bottom = clamp((int) Math.round((rect.y() + rect.height()) * imageHeight), 0, imageHeight);
    if (right <= left || bottom <= top) {
      return List.of();
    }
    return List.of(left, top, right, bottom);
  }

  private int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private void setValue(ObjectNode root, String pageKey, String path, String value) {
    objectChild(root, pageKey).put(path, value);
  }

  private void setConfidence(ObjectNode root, String pageKey, String path, double confidence) {
    ObjectNode confidenceRoot = objectChild(root, "_confidence");
    objectChild(confidenceRoot, pageKey).put(path, Math.round(confidence));
  }

  private void setEvidence(ObjectNode root, String pageKey, FieldSpec field, FieldCropTranscriptionResult result) {
    ObjectNode evidenceRoot = objectChild(root, "_field_evidence");
    ObjectNode pageEvidence = objectChild(evidenceRoot, pageKey);
    ObjectNode evidence = objectChild(pageEvidence, field.path());
    evidence.remove(List.of(
        "selection_crop_status",
        "selection_crop_confidence",
        "selection_crop_text",
        "selection_filtered_out",
        "original_value",
        "filtered_value",
        "excluded_marks",
        "secondary_excluded_marks"
    ));
    evidence.put("label", field.label());
    ObjectNode bbox = evidence.putObject("value_bbox");
    bbox.put("x", field.rect().x());
    bbox.put("y", field.rect().y());
    bbox.put("width", field.rect().width());
    bbox.put("height", field.rect().height());
    evidence.put("footer_crop_text", result.text().trim());
    if (result.excludedMarks().isArray() && !result.excludedMarks().isEmpty()) {
      evidence.put("footer_crop_text_after_excluded_marks", result.textWithoutExcludedMarks());
    }
    evidence.put("footer_crop_confidence", Math.round(result.confidence()));
    evidence.put("footer_crop_status", result.status());
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

  private record FieldSpec(String path, String label, NormalizedRect rect) {}

  private record NormalizedRect(double x, double y, double width, double height) {}

  private record CropResult(byte[] bytes, String dataUrl) {}
}
