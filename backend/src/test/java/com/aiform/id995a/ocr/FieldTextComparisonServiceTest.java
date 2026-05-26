package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FieldTextComparisonServiceTest {

  @Test
  void marksOnlyMismatchedAndLowConfidenceCharacters() {
    FieldTextComparisonService service = new FieldTextComparisonService();

    List<FieldCharacterEvidence> characters = service.compare(
        "AGUIJAR",
        List.of(
            new FieldCharacterEvidence(0, "A", 95, "ok", List.of(0, 0, 10, 10), ""),
            new FieldCharacterEvidence(1, "G", 95, "ok", List.of(10, 0, 20, 10), ""),
            new FieldCharacterEvidence(2, "U", 95, "ok", List.of(20, 0, 30, 10), ""),
            new FieldCharacterEvidence(3, "I", 95, "ok", List.of(30, 0, 40, 10), ""),
            new FieldCharacterEvidence(4, "J", 92, "ok", List.of(40, 0, 50, 10), ""),
            new FieldCharacterEvidence(5, "A", 95, "ok", List.of(50, 0, 60, 10), ""),
            new FieldCharacterEvidence(6, "R", 62, "ok", List.of(60, 0, 70, 10), "")
        ),
        "AGUIAR"
    );

    assertThat(characters).hasSize(7);
    assertThat(characters.get(4).status()).contains("ocr_mismatch");
    assertThat(characters.get(4).ocrText()).isEqualTo("");
    assertThat(characters.get(6).status()).contains("qwen_low_confidence");
    assertThat(characters.get(0).status()).isEqualTo("ok");
  }

  @Test
  void normalizesFieldTextBeforeComparingPhoneDateNameAndDocumentNumber() {
    FieldTextComparisonService service = new FieldTextComparisonService();

    assertThat(service.compare(
        "contactTelephone.no.value",
        "Contact telephone no.",
        "+62 813-456-789",
        evidence("+62 813-456-789"),
        "+62813456789",
        91
    )).allSatisfy(character -> assertThat(character.status()).doesNotContain("ocr_mismatch"));

    assertThat(service.compare(
        "travelDocument.dateOfIssue.value",
        "Date of issue",
        "01/05/2026",
        evidence("01/05/2026"),
        "2026-05-01",
        90
    )).allSatisfy(character -> assertThat(character.status()).doesNotContain("ocr_mismatch"));

    assertThat(service.compare(
        "personal.surnameEn.value",
        "Surname in English",
        "Aguiar  Maria",
        evidence("Aguiar  Maria"),
        "AGUIAR MARIA",
        93
    )).allSatisfy(character -> assertThat(character.status()).doesNotContain("ocr_mismatch"));

    assertThat(service.compare(
        "travelDocument.no.value",
        "Travel document no.",
        " PH-88342115 ",
        evidence(" PH-88342115 "),
        "PH88342115",
        92
    )).allSatisfy(character -> assertThat(character.status()).doesNotContain("ocr_mismatch"));
  }

  @Test
  void filtersPrintedTemplateTextFromLocalOcrBeforeDisplayAndComparison() {
    FieldTextComparisonService service = new FieldTextComparisonService();

    assertThat(service.filterApplicantOcrText(
        "personal.maidenSurname",
        "Maiden surname (if applicable)",
        "WIBOWO",
        "(cable) WIBOWD"
    )).isEqualTo("WIBOWD");

    assertThat(service.filterApplicantOcrText(
        "personal.dateOfBirth",
        "Date of birth",
        "15/08/1995",
        "Date of birth 15 08 1995 yyyy"
    )).isEqualTo("15 08 1995");
  }

  @Test
  void combinesOcrScoreMissingBboxAndFormatValidationIntoCharacterConfidence() {
    FieldTextComparisonService service = new FieldTextComparisonService();

    List<FieldCharacterEvidence> characters = service.compare(
        "travelDocument.no.value",
        "Travel document no.",
        "PH88342115!",
        List.of(
            new FieldCharacterEvidence(0, "P", 96, "ok", List.of(), ""),
            new FieldCharacterEvidence(1, "H", 96, "ok", List.of(10, 0, 20, 10), ""),
            new FieldCharacterEvidence(2, "8", 96, "ok", List.of(20, 0, 30, 10), ""),
            new FieldCharacterEvidence(3, "8", 96, "ok", List.of(30, 0, 40, 10), ""),
            new FieldCharacterEvidence(4, "3", 96, "ok", List.of(40, 0, 50, 10), ""),
            new FieldCharacterEvidence(5, "4", 96, "ok", List.of(50, 0, 60, 10), ""),
            new FieldCharacterEvidence(6, "2", 96, "ok", List.of(60, 0, 70, 10), ""),
            new FieldCharacterEvidence(7, "1", 96, "ok", List.of(70, 0, 80, 10), ""),
            new FieldCharacterEvidence(8, "1", 96, "ok", List.of(80, 0, 90, 10), ""),
            new FieldCharacterEvidence(9, "5", 96, "ok", List.of(90, 0, 100, 10), ""),
            new FieldCharacterEvidence(10, "!", 96, "ok", List.of(100, 0, 110, 10), "")
        ),
        "PH88342115",
        0.62
    );

    assertThat(characters.get(0).status()).contains("no_bbox_evidence");
    assertThat(characters.get(1).status()).contains("ocr_low_score");
    assertThat(characters.get(1).status()).contains("format_invalid");
    assertThat(characters.get(1).confidence()).isLessThan(70);
  }

  private List<FieldCharacterEvidence> evidence(String text) {
    List<FieldCharacterEvidence> characters = new java.util.ArrayList<>();
    List<String> values = text.codePoints()
        .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
        .toList();
    for (int index = 0; index < values.size(); index += 1) {
      characters.add(new FieldCharacterEvidence(index, values.get(index), 95, "ok", List.of(index, 0, index + 1, 10), ""));
    }
    return characters;
  }
}
