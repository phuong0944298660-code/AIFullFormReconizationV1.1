package com.aiform.id995a.ocr;

public record OcrJobPageProgress(
    int page,
    String status,
    int percent,
    String stage,
    String message
) {}
