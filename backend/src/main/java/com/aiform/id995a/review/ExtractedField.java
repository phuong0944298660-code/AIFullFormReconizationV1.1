package com.aiform.id995a.review;

public record ExtractedField(
    String key,
    String label,
    int page,
    boolean present,
    double confidence
) {}
