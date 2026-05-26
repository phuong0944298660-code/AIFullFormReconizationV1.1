package com.aiform.id995a.review;

import java.util.Locale;
import java.util.Map;

public record AttachmentEvidence(
    boolean hasAcceptanceLetter,
    boolean hasApplicantTravelDocumentCopy,
    boolean hasApplicantFinancialProof,
    boolean hasGuardianConsent,
    boolean hasAccommodationProof,
    boolean hasSponsorForm,
    boolean hasSponsorIdentityCopy,
    boolean hasSponsorFinancialProof,
    boolean mainlandApplicationViaSchool,
    boolean dependantDocumentsComplete
) {

  public static AttachmentEvidence empty() {
    return new AttachmentEvidence(false, false, false, false, false, false, false, false, false, false);
  }

  public static AttachmentEvidence fromMetadata(Map<String, String> metadata) {
    return new AttachmentEvidence(
        bool(metadata, "hasAcceptanceLetter"),
        bool(metadata, "hasApplicantTravelDocumentCopy"),
        bool(metadata, "hasApplicantFinancialProof"),
        bool(metadata, "hasGuardianConsent"),
        bool(metadata, "hasAccommodationProof"),
        bool(metadata, "hasSponsorForm"),
        bool(metadata, "hasSponsorIdentityCopy"),
        bool(metadata, "hasSponsorFinancialProof"),
        bool(metadata, "mainlandApplicationViaSchool"),
        bool(metadata, "dependantDocumentsComplete")
    );
  }

  private static boolean bool(Map<String, String> metadata, String key) {
    String value = metadata.get(key);
    if (value == null) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
  }
}
