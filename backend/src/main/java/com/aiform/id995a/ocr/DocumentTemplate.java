package com.aiform.id995a.ocr;

public record DocumentTemplate(
    String templateId,
    String footerId,
    int pageCount,
    double confidence,
    String matchSource,
    String structureHash
) {

  public DocumentTemplate {
    templateId = templateId == null || templateId.isBlank() ? "unknown_0p_unknown" : templateId.trim();
    footerId = footerId == null ? "" : footerId.trim();
    pageCount = Math.max(0, pageCount);
    confidence = Math.max(0, Math.min(100, confidence));
    matchSource = matchSource == null || matchSource.isBlank() ? "unknown" : matchSource.trim();
    structureHash = structureHash == null ? "" : structureHash.trim();
  }

  public static DocumentTemplate unknown(int pageCount, String structureHash) {
    String hash = structureHash == null || structureHash.isBlank() ? "unknown" : structureHash;
    String suffix = hash.length() <= 8 ? hash : hash.substring(0, 8);
    return new DocumentTemplate("unknown_" + Math.max(0, pageCount) + "p_" + suffix, "", pageCount, 65, "structure_hash", hash);
  }

  public boolean isTemplate(String expectedTemplateId) {
    return templateId.equalsIgnoreCase(expectedTemplateId == null ? "" : expectedTemplateId);
  }
}
