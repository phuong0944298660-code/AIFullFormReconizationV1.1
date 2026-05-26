package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class GeneralFieldCropRefinementServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void reviewsAllOrdinaryFieldCropsByTypeWithoutOverridingSpecializedFields() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "email_address", "chunyin.lee@hotmail.com", "", 96, "ok"),
        new FieldCropTranscriptionResult(1, "contact_telephone_no", "9876", "", 94, "ok"),
        new FieldCropTranscriptionResult(1, "name_of_the_helper", "Angelica CRUZ", "", 89, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    String statement = "I have never been refused a visa/entry permit for entry into Hong Kong.";
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "email_address": "chunyin.leehotmail.com",
            "contact_telephone_no": "987",
            "name_of_the_helper": "Angelica Cruz",
            "residential_address": "NoX12",
            "declaration_of_applicant_refused_visa_entry": "I have never been refused a visa/entry permit for entry into Hong Kong."
          },
          "_field_evidence": {
            "page_1": {
              "email_address": {"label": "E-mail address (if any)", "value_bbox": [10, 10, 180, 40]},
              "contact_telephone_no": {"label": "Contact telephone no.", "value_bbox": [10, 45, 100, 75]},
              "name_of_the_helper": {"label": "Name of the Helper", "value_bbox": [10, 80, 180, 110]},
              "residential_address": {"label": "Residential address", "value_bbox": [10, 115, 180, 145]},
              "declaration_of_applicant_refused_visa_entry": {"label": "I have never been refused a visa/entry permit for entry into Hong Kong.", "value_bbox": [10, 150, 180, 180]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(gateway.requests).extracting(FieldCropTranscriptionRequest::path)
        .contains(
            "email_address",
            "email_address.__expanded_crop",
            "contact_telephone_no",
            "contact_telephone_no.__expanded_crop",
            "name_of_the_helper",
            "name_of_the_helper.__expanded_crop"
        );
    assertThat(result.attempted()).isEqualTo(6);
    assertThat(result.updated()).isEqualTo(2);
    assertThat(result.data().at("/page_1/email_address").asText()).isEqualTo("chunyin.lee@hotmail.com");
    assertThat(result.data().at("/page_1/contact_telephone_no").asText()).isEqualTo("9876");
    assertThat(result.data().at("/page_1/name_of_the_helper").asText()).isEqualTo("Angelica Cruz");
    assertThat(result.data().at("/page_1/residential_address").asText()).isEqualTo("NoX12");
    assertThat(result.data().at("/page_1/declaration_of_applicant_refused_visa_entry").asText()).isEqualTo(statement);
    assertThat(result.data().at("/_field_evidence/page_1/email_address/field_crop_review_type").asText())
        .isEqualTo("symbol_sensitive");
    assertThat(result.data().at("/_confidence/page_1/email_address").asInt()).isEqualTo(96);
  }

  @Test
  void clearsOrdinaryFieldWhenCropShowsOnlyRejectedMarks() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "fax_no",
            "",
            "",
            88,
            "blank",
            objectMapper.readTree("[{\"text\":\"scribble\",\"reason\":\"smudged\"}]")
        )
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "fax_no": "XXX"
          },
          "_field_evidence": {
            "page_1": {
              "fax_no": {"label": "Fax no.", "value_bbox": [10, 10, 120, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/fax_no").isNull()).isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/fax_no/field_crop_filtered_out").asBoolean()).isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/fax_no/excluded_marks/0/reason").asText())
        .isEqualTo("smudged");
  }

  @Test
  void keepsNumericSymbolFieldWhenCropTranscriptionDropsTrailingDigit() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "fax_no", "2376882", "", 95, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "fax_no": "23768821"
          },
          "_field_evidence": {
            "page_1": {
              "fax_no": {"label": "Fax no.", "value_bbox": [10, 10, 120, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.data().at("/page_1/fax_no").asText()).isEqualTo("23768821");
    assertThat(result.data().at("/_field_evidence/page_1/fax_no/field_crop_text").asText()).isEqualTo("2376882");
    assertThat(result.data().at("/_field_evidence/page_1/fax_no/field_crop_replacement_applied").asBoolean()).isFalse();
  }

  @Test
  void keepsTextFieldWhenCropTranscriptionDropsTrailingChineseCharacter() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "occupation", "銀行", "", 95, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "occupation": "銀行家"
          },
          "_field_evidence": {
            "page_1": {
              "occupation": {"label": "Occupation", "value_bbox": [10, 10, 120, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.data().at("/page_1/occupation").asText()).isEqualTo("銀行家");
    assertThat(result.data().at("/_field_evidence/page_1/occupation/field_crop_text").asText()).isEqualTo("銀行");
    assertThat(result.data().at("/_field_evidence/page_1/occupation/field_crop_replacement_applied").asBoolean())
        .isFalse();
  }

  @Test
  void keepsEmailWhenCropLooksLikeCommonDomainAutocorrection() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "email_address", "wing.sze@gmail.com.hk", "", 96, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "email_address": "wing.sze@mial.com.hk"
          },
          "_field_evidence": {
            "page_1": {
              "email_address": {"label": "E-mail address (if any)", "value_bbox": [10, 10, 180, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.data().at("/page_1/email_address").asText()).isEqualTo("wing.sze@mial.com.hk");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/field_crop_text").asText())
        .isEqualTo("wing.sze@gmail.com.hk");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/field_crop_replacement_applied").asBoolean())
        .isFalse();
  }

  @Test
  void repairsEmailDomainFromFocusedDomainCropWhenFullCropDropsNarrowLetter() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "email_address", "chinghan@trendea-bank.com.hk", "", 95, "ok"),
        new FieldCropTranscriptionResult(1, "email_address.__email_domain", "chinghan@trendea-bank.com.hk", "", 96, "ok"),
        new FieldCropTranscriptionResult(1, "email_address.__email_hyphen_prefix", "trendeal-", "", 96, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "email_address": "chinghan@trendea-bank.com.hk"
          },
          "_field_evidence": {
            "page_1": {
              "email_address": {"label": "E-mail address (if any)", "value_bbox": [10, 10, 200, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(gateway.requests).extracting(FieldCropTranscriptionRequest::path)
        .containsExactly(
            "email_address",
            "email_address.__expanded_crop",
            "email_address.__email_domain",
            "email_address.__email_hyphen_prefix"
        );
    assertThat(gateway.requests.get(2).currentValue()).isBlank();
    assertThat(gateway.requests.get(3).currentValue()).isBlank();
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/email_address").asText()).isEqualTo("chinghan@trendeal-bank.com.hk");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/email_domain_crop_text").asText())
        .isEqualTo("chinghan@trendea-bank.com.hk");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/email_hyphen_prefix_crop_text").asText())
        .isEqualTo("trendeal-");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/email_hyphen_prefix_crop_replacement_applied").asBoolean())
        .isTrue();
  }

  @Test
  void repairsKnownEmailDomainSeparatorWhenFocusedCropsSplitVisibleNarrowLetter() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "email_address", "chinghan@trendea-c-bank.com.hk", "", 95, "ok"),
        new FieldCropTranscriptionResult(1, "email_address.__email_domain", "trendea-c-bank.com.hk", "", 96, "ok"),
        new FieldCropTranscriptionResult(1, "email_address.__email_hyphen_prefix", "trendea-c", "", 96, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "email_address": "chinghan@trendea-c-bank.com.hk"
          },
          "_field_evidence": {
            "page_1": {
              "email_address": {"label": "E-mail address (if any)", "value_bbox": [10, 10, 200, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/email_address").asText()).isEqualTo("chinghan@trendeac-bank.com.hk");
    assertThat(result.data().at("/_field_evidence/page_1/email_address/email_domain_separator_repair_applied").asBoolean())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/email_address/email_domain_separator_original_value").asText())
        .isEqualTo("chinghan@trendea-c-bank.com.hk");
  }

  @Test
  void keepsExistingSymbolValueWhenCropBlankHasNoRejectedMarks() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "hk_identity_card_no", "", "", 100, "blank")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "hk_identity_card_no": "No"
          },
          "_field_evidence": {
            "page_1": {
              "hk_identity_card_no": {"label": "HK identity card no.", "value_bbox": [10, 10, 80, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.data().at("/page_1/hk_identity_card_no").asText()).isEqualTo("No");
    assertThat(result.data().at("/_field_evidence/page_1/hk_identity_card_no/field_crop_text").asText()).isBlank();
    assertThat(result.data().at("/_field_evidence/page_1/hk_identity_card_no/field_crop_replacement_applied").asBoolean())
        .isFalse();
  }

  @Test
  void repairsEmploymentContractPrefixWhenFirstGlyphIsMisread() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(4, "employment_contract_no", "TH-CON-IDN2026-0411", "", 100, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_4": {
            "employment_contract_no": "TH-CON-IDN2026-0411"
          },
          "_field_evidence": {
            "page_4": {
              "employment_contract_no": {"label": "employment contract no", "value_bbox": [10, 10, 170, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(4)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(3);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_4/employment_contract_no").asText()).isEqualTo("FH-CON-IDN2026-0411");
    assertThat(result.data().at("/_field_evidence/page_4/employment_contract_no/contract_prefix_repair_applied").asBoolean())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_4/employment_contract_no/contract_prefix_original_value").asText())
        .isEqualTo("TH-CON-IDN2026-0411");
  }

  @Test
  void removesLeadingRejectedMarkBeforeEmploymentContractPrefix() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "previous_contract_number", "RFH-CON-IDN20-0612", "", 92, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "previous_contract_number": "RFH-CON-IDN20-0612"
          },
          "_field_evidence": {
            "page_1": {
              "previous_contract_number": {"label": "Contract number", "value_bbox": [10, 10, 180, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(3);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/previous_contract_number").asText()).isEqualTo("FH-CON-IDN20-0612");
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/contract_prefix_repair_applied").asBoolean())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/contract_prefix_repair_reason").asText())
        .isEqualTo("leading_rejected_mark");
  }

  @Test
  void prefersContractSerialFocusedCropWhenNormalCropsDropIdnYearDigits() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "previous_contract_number", "RFH-CON-IDN20-0612", "", 92, "ok"),
        new FieldCropTranscriptionResult(1, "previous_contract_number.__expanded_crop", "RFH-CON-IDN20-0612", "", 92, "ok"),
        new FieldCropTranscriptionResult(1, "previous_contract_number.__contract_serial_crop", "RFH-CON-IDN2026-0612", "", 94, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "previous_contract_number": "RFH-CON-IDN20-0612"
          },
          "_field_evidence": {
            "page_1": {
              "previous_contract_number": {"label": "Contract number", "value_bbox": [10, 10, 180, 40]}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(3);
    assertThat(gateway.requests)
        .extracting(FieldCropTranscriptionRequest::path)
        .containsExactly(
            "previous_contract_number",
            "previous_contract_number.__expanded_crop",
            "previous_contract_number.__contract_serial_crop"
        );
    assertThat(result.data().at("/page_1/previous_contract_number").asText()).isEqualTo("FH-CON-IDN2026-0612");
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/field_crop_text").asText())
        .isEqualTo("RFH-CON-IDN2026-0612");
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/contract_prefix_repair_applied").asBoolean())
        .isTrue();
  }

  @Test
  void chenLiping407ReportRepairsDroppedContractYearWithChineseContractLabel() throws Exception {
    JsonNode report = reportFixture("陈丽萍-407.md");
    ObjectNode structuredData = report.path("structuredData").deepCopy();
    ((ObjectNode) structuredData.path("page_1")).put("合約號碼", "FH-CON-IDN20-0612");
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "合約號碼", "FH-CON-IDN20-0612", "", 92, "ok"),
        new FieldCropTranscriptionResult(1, "合約號碼.__expanded_crop", "FH-CON-IDN20-0612", "", 92, "ok"),
        new FieldCropTranscriptionResult(1, "合約號碼.__contract_serial_crop", "FH-CON-IDN20-0612", "", 92, "ok")
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    RenderedOcrPage page = blankRenderedPage(
        1,
        report.path("pages").get(0).path("imageWidth").asInt(),
        report.path("pages").get(0).path("imageHeight").asInt()
    );

    GeneralFieldCropRefinementResult result = service.refine(
        report.path("filename").asText(),
        structuredData,
        List.of(page),
        modelProfile()
    );

    assertThat(gateway.requests)
        .extracting(FieldCropTranscriptionRequest::path)
        .contains("合約號碼", "合約號碼.__expanded_crop", "合約號碼.__contract_serial_crop");
    assertThat(result.data().at("/page_1/合約號碼").asText()).isEqualTo("FH-CON-IDN2026-0612");
    assertThat(result.data().at("/_field_evidence/page_1/合約號碼/contract_year_repair_applied").asBoolean())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/合約號碼/contract_year_repair_source_year").asText())
        .isEqualTo("2026");
  }

  @Test
  void repairsDroppedContractYearEvenWhenNoFieldCropCanBeRequested() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "contract_date": "2026年4月20日",
            "previous_contract_number": "FH-CON-IDN20-0612"
          },
          "_field_evidence": {
            "page_1": {
              "contract_date": {"label": "Date"},
              "previous_contract_number": {"label": "Contract number"}
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(gateway.requests).isEmpty();
    assertThat(result.data().at("/page_1/previous_contract_number").asText())
        .isEqualTo("FH-CON-IDN2026-0612");
    assertThat(result.data().at("/_field_evidence/page_1/previous_contract_number/contract_year_repair_applied").asBoolean())
        .isTrue();
  }

  @Test
  void removesRejectedMarkFromAnyPositionBeforeApplyingCropText() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "application_reference_no_of_the_helper",
            "FH20X260925INT",
            "",
            96,
            "ok",
            objectMapper.readTree("[{\"text\":\"X\",\"reason\":\"crossed_out\",\"index\":4,\"length\":1}]")
        )
    ));
    GeneralFieldCropRefinementService service = new GeneralFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "application_reference_no_of_the_helper": "FH20X260925INT"
          },
          "_field_evidence": {
            "page_1": {
              "application_reference_no_of_the_helper": {
                "label": "Application Reference No. of the Helper",
                "value_bbox": [10, 10, 190, 40]
              }
            }
          }
        }
        """);

    GeneralFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/application_reference_no_of_the_helper").asText())
        .isEqualTo("FH20260925INT");
    assertThat(result.data().at("/_field_evidence/page_1/application_reference_no_of_the_helper/field_crop_text").asText())
        .isEqualTo("FH20X260925INT");
    assertThat(result.data().at("/_field_evidence/page_1/application_reference_no_of_the_helper/field_crop_text_after_excluded_marks").asText())
        .isEqualTo("FH20260925INT");
  }

  private RenderedOcrPage renderedPage() throws Exception {
    return renderedPage(1);
  }

  private RenderedOcrPage renderedPage(int page) throws Exception {
    BufferedImage image = new BufferedImage(220, 200, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 220, 200);
    graphics.setColor(Color.BLACK);
    graphics.drawString("chunyin.lee@hotmail.com", 20, 30);
    graphics.drawString("9876", 20, 65);
    graphics.drawString("Angelica Cruz", 20, 100);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        page,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        220,
        200
    );
  }

  private RenderedOcrPage blankRenderedPage(int page, int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        page,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        width,
        height
    );
  }

  private JsonNode reportFixture(String filename) throws Exception {
    Path path = Path.of("..", "docs", "5.12_full_tests", "准确率人工报告", filename);
    if (!Files.exists(path)) {
      path = Path.of("docs", "5.12_full_tests", "准确率人工报告", filename);
    }
    String json = Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    return objectMapper.readTree(json);
  }

  private LlmModelProfile modelProfile() {
    return new LlmModelProfile(
        "local-qwen3.6-35b-a3b",
        "local",
        "Qwen3.6-35B-A3B",
        "OpenAI-compatible local gateway",
        "https://apie.zhisuaninfo.com/v1",
        "test-key",
        false,
        true,
        ""
    );
  }

  private static final class FakeFieldCropTranscriptionGateway implements FieldCropTranscriptionGateway {
    private final List<FieldCropTranscriptionResult> results;
    private final List<FieldCropTranscriptionRequest> requests = new ArrayList<>();

    private FakeFieldCropTranscriptionGateway(List<FieldCropTranscriptionResult> results) {
      this.results = List.copyOf(results);
    }

    @Override
    public List<FieldCropTranscriptionResult> transcribeFieldCrops(
        String filename,
        List<FieldCropTranscriptionRequest> crops,
        LlmModelProfile modelProfile
    ) {
      requests.addAll(crops);
      return results;
    }
  }
}
