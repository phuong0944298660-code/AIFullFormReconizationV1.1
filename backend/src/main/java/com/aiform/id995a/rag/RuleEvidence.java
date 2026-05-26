package com.aiform.id995a.rag;

import java.util.List;

public record RuleEvidence(
    String chunkId,
    String title,
    String content,
    String source,
    List<String> ruleIds,
    double keywordScore,
    double vectorScore,
    double hybridScore,
    String matchMode
) {}
