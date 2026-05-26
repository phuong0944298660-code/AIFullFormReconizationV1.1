package com.aiform.id995a.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.review.AttachmentEvidence;
import com.aiform.id995a.review.FieldEvidence;
import com.aiform.id995a.review.FormReviewInput;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

  private final Id995aRuleEngine ruleEngine = new Id995aRuleEngine();

  @Test
  void flagsMissingRequiredIdentityAndSignatureFields() {
    FormReviewInput input = new FormReviewInput(
        Map.of(),
        new FieldEvidence(Map.of(), Map.of(), ""),
        AttachmentEvidence.empty()
    );

    RuleReview review = ruleEngine.review(input);

    assertThat(review.passed()).isFalse();
    assertThat(review.verdict()).isEqualTo(review.blockingReasons().size() + "处不通过");
    assertThat(review.blockingReasons()).anyMatch(reason -> reason.contains("英文姓"));
    assertThat(review.findings()).extracting(RuleFinding::ruleId)
        .contains("A1-001-SURNAME", "A1-002-TRAVEL-DOCUMENT", "A9-001-SIGNATURE");
  }

  @Test
  void passesCompleteLowRiskAdultInstitutionSponsoredApplication() {
    FormReviewInput input = new FormReviewInput(
        Map.of("applicantAge", "19", "sponsorType", "institution"),
        FieldEvidence.allPresent(
            "surnameEn",
            "givenNamesEn",
            "sex",
            "dateOfBirth",
            "placeOfBirth",
            "nationalityOrDomicile",
            "travelDocumentType",
            "travelDocumentNo",
            "travelDocumentIssueDate",
            "travelDocumentExpiryDate",
            "contactPhone",
            "presentAddress",
            "proposedEntryDate",
            "proposedDuration",
            "schoolNameAddress",
            "course",
            "livingCostTotal",
            "financialSupport",
            "photo",
            "declarationSignature",
            "declarationDate"
        ),
        new AttachmentEvidence(true, true, true, false, false, false, false, false, true, false)
    );

    RuleReview review = ruleEngine.review(input);

    assertThat(review.passed()).isTrue();
    assertThat(review.verdict()).isEqualTo("通过");
    assertThat(review.blockingReasons()).isEmpty();
  }

  @Test
  void requiresGuardianConsentAndAccommodationProofForApplicantUnder18() {
    FormReviewInput input = new FormReviewInput(
        Map.of("applicantAge", "17", "sponsorType", "institution"),
        FieldEvidence.allPresent(
            "surnameEn",
            "givenNamesEn",
            "sex",
            "dateOfBirth",
            "placeOfBirth",
            "nationalityOrDomicile",
            "travelDocumentType",
            "travelDocumentNo",
            "travelDocumentIssueDate",
            "travelDocumentExpiryDate",
            "contactPhone",
            "presentAddress",
            "proposedEntryDate",
            "proposedDuration",
            "schoolNameAddress",
            "course",
            "livingCostTotal",
            "financialSupport",
            "photo",
            "declarationSignature",
            "declarationDate"
        ),
        new AttachmentEvidence(true, true, true, false, false, false, false, false, true, false)
    );

    RuleReview review = ruleEngine.review(input);

    assertThat(review.passed()).isFalse();
    assertThat(review.findings()).extracting(RuleFinding::ruleId)
        .contains("DOC-003-GUARDIAN-CONSENT", "DOC-003-ACCOMMODATION");
  }

  @Test
  void requiresDetailsWhenPreviousShortTermStudyIsSelected() {
    FormReviewInput input = new FormReviewInput(
        Map.of("applicantAge", "22", "sponsorType", "institution"),
        FieldEvidence.allPresent(
            "surnameEn",
            "givenNamesEn",
            "sex",
            "dateOfBirth",
            "placeOfBirth",
            "nationalityOrDomicile",
            "travelDocumentType",
            "travelDocumentNo",
            "travelDocumentIssueDate",
            "travelDocumentExpiryDate",
            "contactPhone",
            "presentAddress",
            "proposedEntryDate",
            "proposedDuration",
            "schoolNameAddress",
            "course",
            "livingCostTotal",
            "financialSupport",
            "photo",
            "previousShortTermStudyYes",
            "declarationSignature",
            "declarationDate"
        ),
        new AttachmentEvidence(true, true, true, false, false, false, false, false, true, false)
    );

    RuleReview review = ruleEngine.review(input);

    assertThat(review.passed()).isFalse();
    assertThat(review.findings()).extracting(RuleFinding::ruleId)
        .contains("A8-001-SHORT-COURSE-DETAILS");
  }
}
