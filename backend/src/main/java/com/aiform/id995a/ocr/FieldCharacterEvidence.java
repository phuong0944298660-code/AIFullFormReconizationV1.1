package com.aiform.id995a.ocr;

import java.util.List;

public record FieldCharacterEvidence(
    int index,
    String text,
    double confidence,
    String status,
    List<Integer> bbox,
    String ocrText
) {

  public FieldCharacterEvidence {
    text = text == null ? "" : text;
    confidence = Math.max(0, Math.min(100, confidence));
    status = status == null || status.isBlank() ? "ok" : status;
    bbox = bbox == null ? List.of() : List.copyOf(bbox);
    ocrText = ocrText == null ? "" : ocrText;
  }
}
