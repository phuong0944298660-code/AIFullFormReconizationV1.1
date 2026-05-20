package com.aiform.id995a.ocr;

import java.util.List;

public record OcrTextBlock(
    String label,
    String content,
    List<Integer> bbox,
    boolean userInput
) {}
