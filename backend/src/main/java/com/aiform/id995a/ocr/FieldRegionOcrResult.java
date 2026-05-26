package com.aiform.id995a.ocr;

public record FieldRegionOcrResult(
    String text,
    double confidence,
    String status
) {

  public FieldRegionOcrResult {
    text = text == null ? "" : text;
    confidence = Math.max(0, Math.min(100, confidence));
    status = status == null || status.isBlank() ? "not_run" : status;
  }

  public static FieldRegionOcrResult unavailable(String status) {
    return new FieldRegionOcrResult("", 0, status);
  }
}
