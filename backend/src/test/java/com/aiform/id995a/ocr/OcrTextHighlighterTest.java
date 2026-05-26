package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OcrTextHighlighterTest {

  @Test
  void highlightsLikelyFilledValuesWithoutHighlightingInstructionText() {
    List<OcrTextLine> lines = OcrTextHighlighter.toLines("""
        Surname in English CHAN
        Date of birth 20-04-2026
        Note: Please read the Guidebook for Entry for Study in Hong Kong [ID(E) 996]
        """);

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0).hasUserInput()).isTrue();
    assertThat(lines.get(0).spans())
        .anySatisfy(span -> {
          assertThat(span.text()).isEqualTo("CHAN");
          assertThat(span.userInput()).isTrue();
        });
    assertThat(lines.get(1).spans())
        .anySatisfy(span -> {
          assertThat(span.text()).isEqualTo("20-04-2026");
          assertThat(span.userInput()).isTrue();
        });
    assertThat(lines.get(2).hasUserInput()).isFalse();
  }

  @Test
  void removesConsecutiveStandaloneLayoutNumbersWithoutDroppingSectionHeadings() {
    List<OcrTextLine> lines = OcrTextHighlighter.toLines("""
        Reference barcode
        1
        1
        2
        2
        2
        2
        Note: Please tick as appropriate.
        1. Application Type
        2. Personal Particulars
        """);

    List<String> text = lines.stream()
        .map(line -> line.spans().stream().map(OcrTextSpan::text).reduce("", String::concat))
        .toList();

    assertThat(text).doesNotContain("1", "2");
    assertThat(text).contains(
        "Reference barcode",
        "Note: Please tick as appropriate.",
        "1. Application Type",
        "2. Personal Particulars"
    );
  }
}
