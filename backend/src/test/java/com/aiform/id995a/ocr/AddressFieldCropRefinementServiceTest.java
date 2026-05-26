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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AddressFieldCropRefinementServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void prefersWideAddressCropWhenNormalCropStillDropsNumberAndLowerLine() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "servant_address", "Hong Kong Wong Nai Chung Road No", "", 92, "ok"),
        new FieldCropTranscriptionResult(
            1,
            "servant_address.__address_wide_crop",
            "Hong Kong Wong Nai Chung Road No23 Happy Valley 18/F F Room",
            "No23",
            93,
            "ok"
        )
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "servant_address": "Hong Kong Wong Nai Chung Road No"
          },
          "_field_evidence": {
            "page_1": {
              "servant_address": {
                "label": "Address",
                "value_bbox": [500, 1200, 1200, 1240]
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedLargeAddressPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(gateway.requests)
        .extracting(FieldCropTranscriptionRequest::path)
        .containsExactly("servant_address", "servant_address.__address_wide_crop");
    assertThat(result.data().at("/page_1/servant_address").asText())
        .isEqualTo("Hong Kong Wong Nai Chung Road No23 Happy Valley 18/F F Room");
    assertThat(result.data().at("/_field_evidence/page_1/servant_address/address_number_fragment").asText())
        .isEqualTo("No23");
  }

  @Test
  void keepsWideAddressResultWhenLaterNormalCropReturnsShortPartialText() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "servant_address.__address_wide_crop",
            "Hong Kong Wong Nai Chung Road No23 Happy Valley 18/F F Room",
            "No23",
            93,
            "ok"
        ),
        new FieldCropTranscriptionResult(1, "servant_address", "Hong Kong No Room", "", 92, "ok")
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "servant_address": "Hong Kong Wong Nai Chung Road No"
          },
          "_field_evidence": {
            "page_1": {
              "servant_address": {
                "label": "Address",
                "value_bbox": [500, 1200, 1200, 1240]
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedLargeAddressPage()),
        modelProfile()
    );

    assertThat(result.data().at("/page_1/servant_address").asText())
        .isEqualTo("Hong Kong Wong Nai Chung Road No23 Happy Valley 18/F F Room");
  }

  @Test
  void chenLiping407ReportUsesWideAddressCropForLeftContinuationLine() throws Exception {
    JsonNode report = reportFixture("陈丽萍-407.md");
    ObjectNode structuredData = report.path("structuredData").deepCopy();
    ((ObjectNode) structuredData.path("page_1")).put("住址", "香港島跑馬地黃泥涌道No號");
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "住址", "香港島跑馬地黃泥涌道No號", "", 92, "ok"),
        new FieldCropTranscriptionResult(
            1,
            "住址.__address_wide_crop",
            "香港島跑馬地黃泥涌道No23號樂活臺18樓F室",
            "No23",
            95,
            "ok"
        )
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    RenderedOcrPage page = blankRenderedPage(
        1,
        report.path("pages").get(0).path("imageWidth").asInt(),
        report.path("pages").get(0).path("imageHeight").asInt()
    );

    AddressFieldCropRefinementResult result = service.refine(
        report.path("filename").asText(),
        structuredData,
        List.of(page),
        modelProfile()
    );

    assertThat(gateway.requests)
        .extracting(FieldCropTranscriptionRequest::path)
        .containsExactly("住址", "住址.__address_wide_crop");
    assertThat(result.data().at("/page_1/住址").asText())
        .isEqualTo("香港島跑馬地黃泥涌道No23號樂活臺18樓F室");
  }

  @Test
  void doesNotApplyVisualSmudgeFilterWhenItWouldRemoveMostOfActualChenLipingAddress() throws Exception {
    String fullAddress = "香港島跑馬地黃泥涌道No23號\n樂活臺18樓F室";
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(1, "servant_address.__address_wide_crop", fullAddress, "No23", 95, "ok")
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "servant_address": "香港島跑馬地黃泥涌道No23號\\n樂活臺18樓F室"
          },
          "_field_evidence": {
            "page_1": {
              "servant_address": {
                "label": "住址",
                "value_bbox": {"x": 0.45, "y": 0.28, "width": 0.45, "height": 0.07}
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "陈丽萍-407.pdf",
        structuredData,
        List.of(renderActualChenLipingFirstPage()),
        modelProfile()
    );

    assertThat(result.data().at("/page_1/servant_address").asText())
        .isEqualTo(fullAddress);
    assertThat(result.data().at("/_field_evidence/page_1/servant_address/visual_smudge_filter_applied").asBoolean())
        .isFalse();
  }

  @Test
  void refinesAddressFromFieldCropEvenWhenFirstPassMissesNoPrefix() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "correspondence_address",
            "香港中環德輔道中NO88號國金中心二期2802室",
            "NO88",
            92,
            "ok"
        )
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "correspondence_address": "香港中環德輔道中168號國金中心二期2802室"
          },
          "_confidence": {
            "page_1": {
              "correspondence_address": 90
            }
          },
          "_field_evidence": {
            "page_1": {
              "correspondence_address": {
                "label": "Correspondence address",
                "value_bbox": {"x": 0.10, "y": 0.10, "width": 0.70, "height": 0.20}
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/page_1/correspondence_address").asText())
        .isEqualTo("香港中環德輔道中NO88號國金中心二期2802室");
    assertThat(result.data().at("/_confidence/page_1/correspondence_address").asInt()).isEqualTo(92);
    assertThat(result.data().at("/_field_evidence/page_1/correspondence_address/secondary_transcription_text").asText())
        .isEqualTo("香港中環德輔道中NO88號國金中心二期2802室");
    assertThat(result.data().at("/_field_evidence/page_1/correspondence_address/address_number_fragment").asText())
        .isEqualTo("NO88");

    assertThat(gateway.requests).hasSize(1);
    FieldCropTranscriptionRequest request = gateway.requests.get(0);
    assertThat(request.path()).isEqualTo("correspondence_address");
    assertThat(request.currentValue()).contains("168");
    assertThat(request.cropImageBytes()).isNotEmpty();
    assertThat(request.cropImageDataUrl()).startsWith("data:image/jpeg;base64,");
  }

  @Test
  void replacesScalarPageConfidenceWithFieldConfidenceObjectWhenRefiningAddress() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "correspondence_address",
            "香港中環德輔道中NO88號國金中心二期2802室",
            "NO88",
            92,
            "ok"
        )
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "correspondence_address": "香港中環德輔道中168號國金中心二期2802室"
          },
          "_confidence": {
            "page_1": 90
          },
          "_field_evidence": {
            "page_1": {
              "correspondence_address": {
                "label": "Correspondence address",
                "value_bbox": {"x": 0.10, "y": 0.10, "width": 0.70, "height": 0.20}
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/_confidence/page_1/correspondence_address").asInt()).isEqualTo(92);
  }

  @Test
  void storesExcludedMarksFromAddressFieldCropResult() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of(
        new FieldCropTranscriptionResult(
            1,
            "correspondence_address",
            "No88",
            "No88",
            91,
            "ok",
            objectMapper.readTree("[{\"text\":\"X\",\"reason\":\"smudged\"}]")
        )
    ));
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "correspondence_address": "NoX88"
          },
          "_field_evidence": {
            "page_1": {
              "correspondence_address": {
                "label": "Correspondence address",
                "value_bbox": {"x": 0.10, "y": 0.10, "width": 0.70, "height": 0.20}
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.data().at("/_field_evidence/page_1/correspondence_address/excluded_marks/0/text").asText())
        .isEqualTo("X");
    assertThat(result.data().at("/_field_evidence/page_1/correspondence_address/secondary_excluded_marks/0/reason").asText())
        .isEqualTo("smudged");
  }

  @Test
  void addressCropExtendsBeyondSingleLineBboxForTrailingDigitsAndSecondLine() {
    List<Integer> original = List.of(500, 1200, 1200, 1240);

    List<Integer> expanded = FieldCropper.expandBbox(original, 2480, 3507, FieldCropper.CropKind.ADDRESS);

    assertThat(expanded.get(2)).isGreaterThanOrEqualTo(1395);
    assertThat(expanded.get(3)).isGreaterThanOrEqualTo(1390);
  }

  @Test
  void addressWideCropCoversLeftContinuationLineFromChenLiping407ReportBbox() {
    List<Integer> original = List.of(554, 464, 1006, 560);

    List<Integer> expanded = FieldCropper.expandBbox(original, 1131, 1600, FieldCropper.CropKind.ADDRESS_WIDE);

    assertThat(expanded.get(0)).isLessThanOrEqualTo(170);
    assertThat(expanded.get(2)).isEqualTo(1131);
    assertThat(expanded.get(3)).isGreaterThanOrEqualTo(1080);
  }

  @Test
  void smallAddressCropsAreUpscaledBeforeLlmReview() throws Exception {
    BufferedImage image = new BufferedImage(2480, 3507, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.drawString("Hong Kong Wong Nai Chung Road No23", 520, 1230);
    graphics.drawString("Happy Valley 18/F F Room", 520, 1310);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    RenderedOcrPage page = new RenderedOcrPage(
        1,
        output.toByteArray(),
        "data:image/png;base64,test",
        image.getWidth(),
        image.getHeight()
    );

    FieldCropper.CropResult crop = FieldCropper.crop(
        page,
        List.of(500, 1200, 1200, 1240),
        FieldCropper.CropKind.ADDRESS
    );
    BufferedImage cropImage = ImageIO.read(new ByteArrayInputStream(crop.bytes()));

    assertThat(cropImage.getHeight()).isGreaterThanOrEqualTo(220);
  }

  @Test
  void skipsNonAddressFields() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "contact_telephone_no": "98761234"
          },
          "_field_evidence": {
            "page_1": {
              "contact_telephone_no": {
                "label": "Contact telephone no.",
                "value_bbox": [10, 10, 80, 30]
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isZero();
    assertThat(result.updated()).isZero();
    assertThat(gateway.requests).isEmpty();
    assertThat(result.data().at("/page_1/contact_telephone_no").asText()).isEqualTo("98761234");
  }

  @Test
  void skipsEmailAddressFieldsEvenWhenLabelContainsAddress() throws Exception {
    FakeFieldCropTranscriptionGateway gateway = new FakeFieldCropTranscriptionGateway(List.of());
    AddressFieldCropRefinementService service = new AddressFieldCropRefinementService(gateway, objectMapper);
    JsonNode structuredData = objectMapper.readTree("""
        {
          "page_1": {
            "email_address": "chunyin.leehotmail.com"
          },
          "_field_evidence": {
            "page_1": {
              "email_address": {
                "label": "E-mail address (if any)",
                "value_bbox": [10, 10, 180, 40]
              }
            }
          }
        }
        """);

    AddressFieldCropRefinementResult result = service.refine(
        "sample.pdf",
        structuredData,
        List.of(renderedPage()),
        modelProfile()
    );

    assertThat(result.attempted()).isZero();
    assertThat(result.updated()).isZero();
    assertThat(gateway.requests).isEmpty();
    assertThat(result.data().at("/page_1/email_address").asText()).isEqualTo("chunyin.leehotmail.com");
  }

  private RenderedOcrPage renderedPage() throws Exception {
    BufferedImage image = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 200, 120);
    graphics.setColor(Color.BLACK);
    graphics.drawString("香港中環德輔道中NO88號國金中心二期2802室", 20, 40);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        1,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        200,
        120
    );
  }

  private RenderedOcrPage renderedLargeAddressPage() throws Exception {
    BufferedImage image = new BufferedImage(2480, 3507, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.drawString("Hong Kong Wong Nai Chung Road No23", 520, 1230);
    graphics.drawString("Happy Valley 18/F F Room", 520, 1310);
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

  private RenderedOcrPage renderedPageWithDenseSmudge() throws Exception {
    BufferedImage image = new BufferedImage(260, 100, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 260, 100);
    graphics.setColor(Color.BLACK);
    graphics.drawString("AB", 24, 45);
    for (int offset = 0; offset < 36; offset += 3) {
      graphics.drawLine(122 + offset, 14, 82 + offset, 72);
      graphics.drawLine(82 + offset, 16, 134 + offset, 70);
    }
    graphics.fillRect(98, 24, 34, 28);
    graphics.drawString("CD", 154, 45);
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        1,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        260,
        100
    );
  }

  private RenderedOcrPage renderedPageWithConnectedDenseSmudge() throws Exception {
    BufferedImage image = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 900, 260);
    graphics.setColor(Color.BLACK);
    graphics.drawString("Residential address", 20, 24);
    int left = 60;
    int top = 86;
    int width = 70;
    for (int index = 0; index < 11; index += 1) {
      int x = left + index * width;
      graphics.drawLine(x + 2, top + 8, x + 68, top + 62);
      graphics.drawLine(x + 2, top + 62, x + 68, top + 8);
      graphics.drawLine(x + 8, top + 4, x + 64, top + 68);
      if (index == 5) {
        graphics.fillRect(x + 5, top + 8, 60, 54);
        for (int offset = 0; offset < 45; offset += 6) {
          graphics.drawLine(x + 4 + offset, top + 4, x + 26 + offset, top + 72);
          graphics.drawLine(x + 4 + offset, top + 72, x + 26 + offset, top + 4);
        }
      }
    }
    graphics.dispose();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    byte[] bytes = output.toByteArray();
    return new RenderedOcrPage(
        1,
        bytes,
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes),
        900,
        260
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

  private RenderedOcrPage renderActualChenLipingFirstPage() throws Exception {
    Path path = Path.of("..", "docs", "5.12_full_tests", "陈丽萍-407.pdf");
    if (!Files.exists(path)) {
      path = Path.of("docs", "5.12_full_tests", "陈丽萍-407.pdf");
    }
    return new BaiduOcrPageRenderer(BaiduOcrPageRenderer.DEFAULT_RENDER_DPI, 0)
        .render(path.getFileName().toString(), "application/pdf", Files.readAllBytes(path))
        .get(0);
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
        "dashscope-qwen3.6-plus",
        "qwen3.6-plus",
        "qwen3.6-plus",
        "DashScope OpenAI-compatible",
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "test-key",
        true,
        false,
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
