package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SmudgedFieldValueFilterServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SmudgedFieldValueFilterService service = new SmudgedFieldValueFilterService(objectMapper);

  @Test
  void removesCharactersMarkedAsSmudgedFromMixedFieldValue() throws Exception {
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "name": "ABXCD"
          },
          "_field_evidence": {
            "page_1": {
              "name": {
                "label": "Name",
                "value_bbox": [10, 10, 110, 30],
                "char_confidences": [
                  {"char": "A", "index": 0, "confidence": 96},
                  {"char": "B", "index": 1, "confidence": 96},
                  {"char": "X", "index": 2, "confidence": 20, "status": "smudged"},
                  {"char": "C", "index": 3, "confidence": 96},
                  {"char": "D", "index": 4, "confidence": 96}
                ]
              }
            }
          }
        }
        """);

    SmudgedFieldValueFilterResult result = service.filter(structuredData);

    assertThat(result.filtered()).isEqualTo(1);
    assertThat(result.data().at("/page_1/name").asText()).isEqualTo("ABCD");
    assertThat(result.data().at("/_field_evidence/page_1/name/smudge_filter_applied").asBoolean()).isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/name/original_value").asText()).isEqualTo("ABXCD");
    assertThat(result.data().at("/_field_evidence/page_1/name/filtered_value").asText()).isEqualTo("ABCD");
  }

  @Test
  void treatsFieldAsBlankWhenAllCharactersAreExcludedMarks() throws Exception {
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "fax_no": "XXX"
          },
          "_field_evidence": {
            "page_1": {
              "fax_no": {
                "label": "Fax no.",
                "char_confidences": [
                  {"char": "X", "index": 0, "status": "crossed_out"},
                  {"char": "X", "index": 1, "status": "smudged"},
                  {"char": "X", "index": 2, "status": "erased"}
                ]
              }
            }
          }
        }
        """);

    SmudgedFieldValueFilterResult result = service.filter(structuredData);

    assertThat(result.filtered()).isEqualTo(1);
    assertThat(result.data().at("/page_1/fax_no").isNull()).isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/fax_no/filtered_value").isNull()).isTrue();
  }

  @Test
  void removesUniqueExcludedMarkTextWhenItWasMixedIntoValue() throws Exception {
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "contact_telephone_no": "12A34"
          },
          "_field_evidence": {
            "page_1": {
              "contact_telephone_no": {
                "label": "Contact telephone no.",
                "excluded_marks": [
                  {"text": "A", "reason": "correction"}
                ]
              }
            }
          }
        }
        """);

    SmudgedFieldValueFilterResult result = service.filter(structuredData);

    assertThat(result.filtered()).isEqualTo(1);
    assertThat(result.data().at("/page_1/contact_telephone_no").asText()).isEqualTo("1234");
  }

  @Test
  void removesLeadingRejectedMarkBeforeVisibleEmploymentContractNumber() throws Exception {
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "previous_contract_number": "RFH-CON-IDN2024-0612"
          },
          "_field_evidence": {
            "page_1": {
              "previous_contract_number": {
                "label": "Contract number",
                "value_bbox": [10, 10, 180, 40]
              }
            }
          }
        }
        """);

    SmudgedFieldValueFilterResult result = service.filter(structuredData);

    assertThat(result.filtered()).isEqualTo(1);
    assertThat(result.data().at("/page_1/previous_contract_number").asText())
        .isEqualTo("FH-CON-IDN2024-0612");
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/leading_rejected_contract_mark_filtered").asBoolean())
        .isTrue();
  }

  @Test
  void doesNotApplyExcludedMarkIndexesAgainAfterVisualCropFilterAlreadyRan() throws Exception {
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "residential_address": "\u9999\u6e2f\u4e5d\u9f8d\u4f55\u6587\u7530\u52dd\u5229\u9053"
          },
          "_field_evidence": {
            "page_1": {
              "residential_address": {
                "label": "Residential address",
                "visual_smudge_filter_applied": true,
                "visual_original_value": "\u9999\u6e2f\u4e5d\u9f8d\u4f55\u6587\u7530\u9a30\u52dd\u5229\u9053",
                "visual_filtered_value": "\u9999\u6e2f\u4e5d\u9f8d\u4f55\u6587\u7530\u52dd\u5229\u9053",
                "excluded_marks": [
                  {"text": "\u9a30", "reason": "smudged", "index": 7, "length": 1}
                ]
              }
            }
          }
        }
        """);

    SmudgedFieldValueFilterResult result = service.filter(structuredData);

    assertThat(result.filtered()).isZero();
    assertThat(result.data().at("/page_1/residential_address").asText())
        .isEqualTo("\u9999\u6e2f\u4e5d\u9f8d\u4f55\u6587\u7530\u52dd\u5229\u9053");
    assertThat(result.data().at("/_field_evidence/page_1/residential_address/smudge_filter_applied").asBoolean())
        .isFalse();
  }
}
