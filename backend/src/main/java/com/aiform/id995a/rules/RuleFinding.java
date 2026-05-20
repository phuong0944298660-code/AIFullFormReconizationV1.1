package com.aiform.id995a.rules;

public record RuleFinding(
    String ruleId,
    RuleSeverity severity,
    String title,
    String message,
    String source,
    int page,
    String fieldKey
) {}
