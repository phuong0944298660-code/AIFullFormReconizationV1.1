package com.aiform.id995a.review;

import com.aiform.id995a.rules.RuleSeverity;

public record DocumentAnnotation(
    String id,
    int page,
    double x,
    double y,
    double width,
    double height,
    String label,
    String message,
    RuleSeverity severity
) {}
