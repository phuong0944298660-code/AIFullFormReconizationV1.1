package com.aiform.id995a.ocr;

import java.util.List;

public record Id988aFieldExtraction(
    String sectionKey,
    String sectionName,
    int page,
    String key,
    String label,
    String fieldType,
    String normalizedKey,
    String option,
    String value,
    boolean present,
    double confidence,
    TemplateRect groupBox,
    TemplateRect valueBox,
    List<Integer> groupBbox,
    List<Integer> valueBbox,
    String extractionSource,
    String qualityStatus,
    List<String> qualityReasons
) {}
