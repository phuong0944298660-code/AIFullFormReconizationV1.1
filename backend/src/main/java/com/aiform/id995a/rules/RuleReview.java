package com.aiform.id995a.rules;

import java.util.List;

public record RuleReview(
    boolean passed,
    String verdict,
    List<RuleFinding> findings,
    List<String> blockingReasons,
    List<String> manualReviewReasons
) {

  public static RuleReview from(List<RuleFinding> findings) {
    List<String> blockingReasons = findings.stream()
        .filter(finding -> finding.severity() == RuleSeverity.BLOCKING)
        .map(RuleFinding::message)
        .toList();
    List<String> manualReasons = findings.stream()
        .filter(finding -> finding.severity() == RuleSeverity.MANUAL_REVIEW)
        .map(RuleFinding::message)
        .toList();

    boolean passed = blockingReasons.isEmpty();
    String verdict = passed ? "通过" : blockingReasons.size() + "处不通过";
    return new RuleReview(passed, verdict, List.copyOf(findings), blockingReasons, manualReasons);
  }
}
