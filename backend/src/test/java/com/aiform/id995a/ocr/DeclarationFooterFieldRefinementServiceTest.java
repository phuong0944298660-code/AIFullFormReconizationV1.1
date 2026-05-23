package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiform.id995a.llm.FieldCropTranscriptionGateway;
import com.aiform.id995a.llm.FieldCropTranscriptionRequest;
import com.aiform.id995a.llm.FieldCropTranscriptionResult;
import com.aiform.id995a.llm.LlmModelProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class DeclarationFooterFieldRefinementServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void restoresMissingDeclarationFooterDateAndSignatureFromPageCrop() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(3, "date", "03/09/2026", "", 92, "ok"),
        new FieldCropTranscriptionResult(3, "signature_of_applicant", "Josefina M. Aguilar", "", 90, "ok")
    ));
    DeclarationFooterFieldRefinementService service = new DeclarationFooterFieldRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_previous_name": "Josefina Maria DELA CRUZ"
          },
          "_field_evidence": {
            "page_3": {
              "declaration_of_applicant_previous_name": {
                "label": "I have used the following name(s) before:"
              }
            }
          }
        }
        """);

    DeclarationFooterFieldRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(3);
    assertThat(result.updated()).isEqualTo(2);
    assertThat(result.data().at("/page_3/date").asText()).isEqualTo("03/09/2026");
    assertThat(result.data().at("/page_3/signature_of_applicant").asText()).isEqualTo("Josefina M. Aguilar");
    assertThat(result.data().at("/_field_evidence/page_3/date/value_bbox/x").asDouble()).isGreaterThan(0.2);
    assertThat(result.data().at("/_field_evidence/page_3/signature_of_applicant/footer_crop_status").asText()).isEqualTo("ok");
    assertThat(gateway.requests).hasSize(3);
    assertThat(gateway.requests).extracting(FieldCropTranscriptionRequest::path)
        .containsExactly("declaration_of_applicant_refused_visa_entry", "date", "signature_of_applicant");
  }

  @Test
  void restoresMissingRefusedVisaDeclarationWhenCropConfirmsSelection() throws Exception {
    String statement = "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.";
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(3, "declaration_of_applicant_refused_visa_entry", statement, "", 91, "ok")
    ));
    DeclarationFooterFieldRefinementService service = new DeclarationFooterFieldRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_previous_name": "Josefina Maria DELA CRUZ",
            "date": "05/04/2026",
            "signature_of_applicant": "Josefina M. Aguilar"
          }
        }
        """);

    DeclarationFooterFieldRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_3/declaration_of_applicant_refused_visa_entry").asText()).isEqualTo(statement);
    assertThat(result.data().at("/_field_evidence/page_3/declaration_of_applicant_refused_visa_entry/footer_crop_status").asText())
        .isEqualTo("ok");
  }

  @Test
  void clearsStaleSelectionEvidenceWhenFooterCropRestoresDeclaration() throws Exception {
    String statement = "I have never been refused a visa/entry permit for entry into Hong Kong and have never been refused entry into, deported from, removed from or required to leave Hong Kong.";
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(3, "declaration_of_applicant_refused_visa_entry", statement, "", 95, "ok")
    ));
    DeclarationFooterFieldRefinementService service = new DeclarationFooterFieldRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_3": {
            "declaration_of_applicant_previous_name": "Josefina Maria DELA CRUZ",
            "declaration_of_applicant_refused_visa_entry": null,
            "date": "05/04/2026",
            "signature_of_applicant": "Josefina M. Aguilar"
          },
          "_field_evidence": {
            "page_3": {
              "declaration_of_applicant_refused_visa_entry": {
                "label": "I have never been refused a visa/entry permit for entry into Hong Kong...",
                "selection_crop_status": "blank",
                "selection_filtered_out": true,
                "original_value": "I have never been refused...",
                "filtered_value": "",
                "excluded_marks": ["scribble correction"],
                "secondary_excluded_marks": ["scribble correction"]
              }
            }
          }
        }
        """);

    DeclarationFooterFieldRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(3)),
        modelProfile()
    );

    JsonNode evidence = result.data().at("/_field_evidence/page_3/declaration_of_applicant_refused_visa_entry");
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_3/declaration_of_applicant_refused_visa_entry").asText()).isEqualTo(statement);
    assertThat(evidence.at("/selection_crop_status").isMissingNode()).isTrue();
    assertThat(evidence.at("/selection_filtered_out").isMissingNode()).isTrue();
    assertThat(evidence.at("/excluded_marks").isMissingNode()).isTrue();
    assertThat(evidence.at("/secondary_excluded_marks").isMissingNode()).isTrue();
    assertThat(evidence.at("/footer_crop_status").asText()).isEqualTo("ok");
  }

  @Test
  void skipsFooterSupplementWhenPageDoesNotLookLikeDeclarationPage() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    DeclarationFooterFieldRefinementService service = new DeclarationFooterFieldRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "name": "LEE CHUN YIN"
          }
        }
        """);

    DeclarationFooterFieldRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage(1)),
        modelProfile()
    );

    assertThat(result.attempted()).isZero();
    assertThat(result.updated()).isZero();
    assertThat(gateway.requests).isEmpty();
  }

  private RenderedOcrPage renderedPage(int page) throws Exception {
    BufferedImage image = new BufferedImage(2480, 3507, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.drawString("Date 03/09/2026", 900, 3150);
    graphics.drawString("Signature of applicant Josefina M. Aguilar", 1500, 3150);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        page,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        image.getWidth(),
        image.getHeight()
    );
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
