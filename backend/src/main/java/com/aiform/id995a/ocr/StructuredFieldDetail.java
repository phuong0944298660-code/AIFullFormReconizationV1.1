package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record StructuredFieldDetail(
    int page,
    String path,
    String label,
    JsonNode value,
    String displayValue,
    double confidence,
    List<Integer> bbox,
    String snapshotDataUrl,
    String ocrText,
    double ocrConfidence,
    String ocrStatus,
    List<FieldCharacterEvidence> characters
) {

  public StructuredFieldDetail {
    path = path == null ? "" : path;
    label = label == null || label.isBlank() ? path : label;
    displayValue = displayValue == null ? "" : displayValue;
    confidence = Math.max(0, Math.min(100, confidence));
    bbox = bbox == null ? List.of() : List.copyOf(bbox);
    snapshotDataUrl = snapshotDataUrl == null ? "" : snapshotDataUrl;
    ocrText = ocrText == null ? "" : ocrText;
    ocrConfidence = Math.max(0, Math.min(100, ocrConfidence));
    ocrStatus = ocrStatus == null || ocrStatus.isBlank() ? "not_run" : ocrStatus;
    characters = characters == null ? List.of() : List.copyOf(characters);
  }
}
