package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SelectionFieldCropRefinementServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void clearsCheckboxStatementWhenCropShowsOnlyRejectedMark() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            3,
            "declaration_of_applicant_convicted",
            "",
            "",
            92,
            "blank",
            objectMapper.readTree("[{\"text\":\"scribble\",\"reason\":\"smudged\"}]")
        )
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_convicted": "I have previously been convicted of crime(s) or offence(s) in Hong Kong or elsewhere. The date(s) and details are as follows:"
          },
          "_field_evidence": {
            "page_3": {
              "declaration_of_applicant_convicted": {
                "label": "I have previously been convicted of crime(s) or offence(s) in Hong Kong or elsewhere. The date(s) and details are as follows:",
                "value_bbox": {"x": 0.06, "y": 0.55, "width": 0.80, "height": 0.08}
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_3/declaration_of_applicant_convicted").isMissingNode()).isTrue();
    assertThat(result.data().at("/_field_evidence/page_3/declaration_of_applicant_convicted/selection_crop_status").asText())
        .isEqualTo("blank");
    assertThat(result.data().at("/_field_evidence/page_3/declaration_of_applicant_convicted/selection_filtered_out").asBoolean())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_3/declaration_of_applicant_convicted/excluded_marks/0/reason").asText())
        .isEqualTo("smudged");
    assertThat(gateway.requests).hasSize(1);
    assertThat(gateway.requests.get(0).path()).isEqualTo("declaration_of_applicant_convicted");
  }

  @Test
  void keepsCheckboxStatementWhenCropConfirmsClearSelection() throws Exception {
    String statement = "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.";
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(3, "declaration_of_applicant_visa_refused", statement, "", 94, "ok")
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_visa_refused": "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong."
          },
          "_field_evidence": {
            "page_3": {
              "declaration_of_applicant_visa_refused": {
                "label": "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.",
                "value_bbox": [10, 20, 180, 50]
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isZero();
    assertThat(result.data().at("/page_3/declaration_of_applicant_visa_refused").asText()).isEqualTo(statement);
  }

  @Test
  void restoresNullCheckboxStatementWhenCropConfirmsClearSelection() throws Exception {
    String statement = "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.";
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(3, "declaration_of_applicant_visa_refused", statement, "", 94, "ok")
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_visa_refused": null
          },
          "_field_evidence": {
            "page_3": {
              "declaration_of_applicant_visa_refused": {
                "label": "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.",
                "value_bbox": [10, 20, 180, 50]
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_3/declaration_of_applicant_visa_refused").asText()).isEqualTo(statement);
    assertThat(gateway.requests).hasSize(1);
    assertThat(gateway.requests.get(0).currentValue()).isBlank();
  }

  @Test
  void skipsOrdinaryTypedAndHandwrittenFields() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "date": "05/04/2026",
            "signature_of_applicant": "Josefina M. Aguilar"
          },
          "_field_evidence": {
            "page_3": {
              "date": {"label": "Date", "value_bbox": [10, 20, 80, 40]},
              "signature_of_applicant": {"label": "Signature of applicant", "value_bbox": [10, 50, 160, 80]}
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isZero();
    assertThat(result.updated()).isZero();
    assertThat(gateway.requests).isEmpty();
  }

  @Test
  void restoresMissingSeparateServantRoomFromBedroomRowCrop() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(2, "separate_servant_room", "有", "", 96, "ok")
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_2": {
            "number_of_bedroom": "3"
          },
          "_field_evidence": {
            "page_2": {
              "number_of_bedroom": {
                "label": "Number of bedroom(s)",
                "value_bbox": [45, 50, 62, 70]
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(2)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_2/separate_servant_room").asText()).isEqualTo("有");
    assertThat(result.data().at("/_confidence/page_2/separate_servant_room").asInt()).isEqualTo(96);
    assertThat(result.data().at("/_field_evidence/page_2/separate_servant_room/label").asText())
        .contains("Separate servant room");
    assertThat(result.data().at("/_field_evidence/page_2/separate_servant_room/value_bbox/x").asDouble()).isGreaterThan(0);
    assertThat(result.data().at("/_field_evidence/page_2/separate_servant_room/selection_crop_text").asText()).isEqualTo("有");
    assertThat(gateway.requests).hasSize(1);
    assertThat(gateway.requests.get(0).path()).isEqualTo("separate_servant_room");
    assertThat(gateway.requests.get(0).label()).contains("獨立工人房");
    assertThat(gateway.requests.get(0).currentValue()).isBlank();
  }

  @Test
  void restoresMissingHouseholdIncomeDeclarationFromIncomeRowCrop() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(2, "average_monthly_household_income_no_less_than_hk15000", "Yes", "", 96, "ok")
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_2": {
            "average_monthly_household_income_no_less_than": "95000"
          },
          "_field_evidence": {
            "page_2": {
              "average_monthly_household_income_no_less_than": {
                "label": "Average monthly household income no less than: HK$",
                "value_bbox": [105, 50, 145, 72]
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(2)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_2/average_monthly_household_income_no_less_than_hk15000").asText()).isEqualTo("Yes");
    assertThat(result.data().at("/_field_evidence/page_2/average_monthly_household_income_no_less_than_hk15000/label").asText())
        .contains("household income");
    assertThat(result.data().at("/_field_evidence/page_2/average_monthly_household_income_no_less_than_hk15000/selection_crop_text").asText())
        .isEqualTo("Yes");
    assertThat(gateway.requests).hasSize(1);
    assertThat(gateway.requests.get(0).path()).isEqualTo("average_monthly_household_income_no_less_than_hk15000");
    assertThat(gateway.requests.get(0).currentValue()).isBlank();
  }

  @Test
  void restoresMissingHkIdentityCardNoSelectionFromNearbyRowCrop() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "hk_identity_card_no", "No", "", 96, "ok")
    ));
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "hk_identity_card_no": null,
            "nationality": "Indonesian"
          },
          "_field_evidence": {
            "page_1": {
              "nationality": {
                "label": "Nationality",
                "value_bbox": [130, 72, 165, 92]
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(1)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/hk_identity_card_no").asText()).isEqualTo("No");
    assertThat(result.data().at("/_field_evidence/page_1/hk_identity_card_no/label").asText())
        .contains("HK identity card no.");
    assertThat(result.data().at("/_field_evidence/page_1/hk_identity_card_no/selection_crop_text").asText()).isEqualTo("No");
    assertThat(gateway.requests).hasSize(1);
    assertThat(gateway.requests.get(0).path()).isEqualTo("hk_identity_card_no");
  }

  @Test
  void restoresApplicationTypeRowsFromFirstPageCheckboxes() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "application_type": "Entry visa"
          },
          "_field_evidence": {
            "page_1": {
              "application_type": {
                "label": "Application Type",
                "value_bbox": {"x": 0.72, "y": 0.35, "width": 0.16, "height": 0.07}
              }
            }
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedApplicationTypePage(true, true, false, false)),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(2);
    assertThat(result.data().at("/page_1/application_type/entry_to_hong_kong_to_take_up_employment_as_a_domestic_helper_from_abroad").asText())
        .isEqualTo("entry visa");
    assertThat(result.data().at("/page_1/application_type/contract_renewal_with_the_same_employer_or_change_of_employer").asText())
        .isEqualTo("entry visa");
    assertThat(result.data().at("/page_1/application_type/complete_the_remaining_extended_period_of_the_current_contract").isMissingNode())
        .isTrue();
    assertThat(result.data().at("/_field_evidence/page_1/application_type/contract_renewal_with_the_same_employer_or_change_of_employer/label").asText())
        .isEqualTo("Contract renewal with the same employer or change of employer");
  }

  @Test
  void restoresContractRenewalExtensionChoiceFromLowerApplicationTypeCheckbox() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "application_type": "Entry visa"
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedApplicationTypePage(false, false, true, false)),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/application_type/contract_renewal_with_the_same_employer_or_change_of_employer").asText())
        .isEqualTo("entry visa AND Extension of Stay");
    assertThat(result.data().at("/page_1/application_type/entry_to_hong_kong_to_take_up_employment_as_a_domestic_helper_from_abroad").isMissingNode())
        .isTrue();
  }

  @Test
  void restoresApplicationTypeRowsFromActualHuangXiaolanFirstPage() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    SelectionFieldCropRefinementService service = new SelectionFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "application_type": "Entry visa"
          }
        }
        """);

    SelectionFieldCropRefinementResult result = service.refine(
        "黄晓兰A.pdf",
        structuredData,
        List.of(renderActualHuangXiaolanFirstPage()),
        modelProfile()
    );

    assertThat(result.data().at("/page_1/application_type/entry_to_hong_kong_to_take_up_employment_as_a_domestic_helper_from_abroad").asText())
        .isEqualTo("entry visa");
    assertThat(result.data().at("/page_1/application_type/contract_renewal_with_the_same_employer_or_change_of_employer").asText())
        .isEqualTo("entry visa");
    assertThat(result.data().at("/page_1/application_type/complete_the_remaining_extended_period_of_the_current_contract").isMissingNode())
        .isTrue();
  }

  private RenderedOcrPage renderedPage(int page) throws Exception {
    BufferedImage image = new BufferedImage(220, 140, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 220, 140);
    graphics.setColor(Color.BLACK);
    graphics.drawRect(16, 80, 14, 14);
    graphics.drawString("I have previously been convicted", 36, 92);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        page,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        220,
        140
    );
  }

  private RenderedOcrPage renderedApplicationTypePage(
      boolean entryVisa,
      boolean contractRenewalEntryVisa,
      boolean contractRenewalEntryVisaAndExtension,
      boolean remainingContractExtension
  ) throws Exception {
    BufferedImage image = new BufferedImage(1131, 1600, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.drawString("Application Type", 110, 540);
    graphics.drawString("Entry to Hong Kong to take up employment as a domestic helper from abroad", 120, 650);
    graphics.drawString("Contract renewal with the same employer or change of employer", 120, 735);
    graphics.drawString("Complete the remaining/extended period of the current contract", 120, 940);
    drawCheckbox(graphics, image, 0.755, 0.305);
    drawCheckbox(graphics, image, 0.755, 0.378);
    drawCheckbox(graphics, image, 0.755, 0.440);
    drawCheckbox(graphics, image, 0.755, 0.505);
    if (entryVisa) {
      drawBlueTick(graphics, image, 0.755, 0.305);
    }
    if (contractRenewalEntryVisa) {
      drawBlueTick(graphics, image, 0.755, 0.378);
    }
    if (contractRenewalEntryVisaAndExtension) {
      drawBlueTick(graphics, image, 0.755, 0.440);
    }
    if (remainingContractExtension) {
      drawBlueTick(graphics, image, 0.755, 0.505);
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        1,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        image.getWidth(),
        image.getHeight()
    );
  }

  private void drawCheckbox(Graphics2D graphics, BufferedImage image, double centerX, double centerY) {
    int size = 24;
    int x = (int) Math.round(image.getWidth() * centerX) - size / 2;
    int y = (int) Math.round(image.getHeight() * centerY) - size / 2;
    graphics.setColor(Color.BLACK);
    graphics.setStroke(new BasicStroke(2f));
    graphics.drawRect(x, y, size, size);
  }

  private void drawBlueTick(Graphics2D graphics, BufferedImage image, double centerX, double centerY) {
    int x = (int) Math.round(image.getWidth() * centerX);
    int y = (int) Math.round(image.getHeight() * centerY);
    graphics.setColor(new Color(75, 105, 245));
    graphics.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    graphics.drawLine(x - 13, y + 3, x - 4, y + 13);
    graphics.drawLine(x - 4, y + 13, x + 21, y - 32);
  }

  private RenderedOcrPage renderActualHuangXiaolanFirstPage() throws Exception {
    Path path = Path.of("..", "docs", "5.12_full_tests", "黄晓兰A.pdf");
    if (!Files.exists(path)) {
      path = Path.of("docs", "5.12_full_tests", "黄晓兰A.pdf");
    }
    return new BaiduOcrPageRenderer(BaiduOcrPageRenderer.DEFAULT_RENDER_DPI, 0)
        .render(path.getFileName().toString(), "application/pdf", Files.readAllBytes(path))
        .get(0);
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
