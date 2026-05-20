package com.aiform.id995a.ocr;

import java.util.List;

public record OcrCheckbox(
    int index,
    boolean checked,
    double confidence,
    String label,
    List<Integer> bbox
) {}
