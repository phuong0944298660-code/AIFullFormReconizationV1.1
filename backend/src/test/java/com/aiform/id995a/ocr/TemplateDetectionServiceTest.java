package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class TemplateDetectionServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void detectsKnownTemplateFromPdfFooterText() throws Exception {
    FakeFieldRegionOcrGateway fieldOcr = new FakeFieldRegionOcrGateway(List.of());
    TemplateDetectionService service = new TemplateDetectionService(fieldOcr, objectMapper);

    DocumentTemplate template = service.detect(
        "any-name.pdf",
        "application/pdf",
        pdfWithFooter("ID 988A (06/2024)"),
        List.of(renderedPage(1), renderedPage(2), renderedPage(3), renderedPage(4), renderedPage(5))
    );

    assertThat(template.templateId()).isEqualTo("id988a_2024_06");
    assertThat(template.footerId()).isEqualTo("ID 988A (06/2024)");
    assertThat(template.matchSource()).isEqualTo("pdf_text_footer");
    assertThat(template.pageCount()).isEqualTo(5);
    assertThat(fieldOcr.requests).isEmpty();
  }

  @Test
  void fallsBackToFooterCropOcrWhenPdfTextHasNoFooter() throws Exception {
    FakeFieldRegionOcrGateway fieldOcr = new FakeFieldRegionOcrGateway(List.of(
        new FieldRegionOcrResult("ID 988B (06/2024)", 96, "available")
    ));
    TemplateDetectionService service = new TemplateDetectionService(fieldOcr, objectMapper);

    DocumentTemplate template = service.detect(
        "scanned-upload.pdf",
        "application/pdf",
        pdfWithFooter(""),
        List.of(renderedPage(1), renderedPage(2), renderedPage(3), renderedPage(4), renderedPage(5))
    );

    assertThat(template.templateId()).isEqualTo("id988b_2024_06");
    assertThat(template.footerId()).isEqualTo("ID 988B (06/2024)");
    assertThat(template.matchSource()).isEqualTo("footer_ocr");
    assertThat(fieldOcr.requests).isNotEmpty();
  }

  @Test
  void generatesStableUnknownTemplateIdWhenFooterCannotBeRead() throws Exception {
    FakeFieldRegionOcrGateway fieldOcr = new FakeFieldRegionOcrGateway(List.of(
        FieldRegionOcrResult.unavailable("blank"),
        FieldRegionOcrResult.unavailable("blank")
    ));
    TemplateDetectionService service = new TemplateDetectionService(fieldOcr, objectMapper);

    DocumentTemplate first = service.detect(
        "first.pdf",
        "application/pdf",
        pdfWithFooter(""),
        List.of(renderedPage(1), renderedPage(2))
    );
    DocumentTemplate second = service.detect(
        "renamed.pdf",
        "application/pdf",
        pdfWithFooter(""),
        List.of(renderedPage(1), renderedPage(2))
    );

    assertThat(first.templateId()).startsWith("unknown_2p_");
    assertThat(second.templateId()).isEqualTo(first.templateId());
    assertThat(first.matchSource()).isEqualTo("structure_hash");
    assertThat(first.footerId()).isBlank();
  }

  @Test
  void detectsActualKnownTemplateFamiliesWithoutUsingFilename() throws Exception {
    FakeFieldRegionOcrGateway fieldOcr = new FakeFieldRegionOcrGateway(List.of());
    TemplateDetectionService service = new TemplateDetectionService(fieldOcr, objectMapper);
    BaiduOcrPageRenderer renderer = new BaiduOcrPageRenderer(160f);

    DocumentTemplate template988a = detectActual(service, renderer, fileContaining("A(P1"));
    DocumentTemplate template988b = detectActual(service, renderer, fileEndingWith("B.pdf"));
    DocumentTemplate template407 = detectActual(service, renderer, fileContaining("-407"));

    assertThat(template988a.templateId()).isEqualTo("id988a_2024_06");
    assertThat(template988a.footerId()).isEqualTo("ID 988A (06/2024)");
    assertThat(template988a.matchSource()).isEqualTo("visual_layout_footer");
    assertThat(template988b.templateId()).isEqualTo("id988b_2024_06");
    assertThat(template988b.footerId()).isEqualTo("ID 988B (06/2024)");
    assertThat(template407.templateId()).isEqualTo("id407_2016_11");
    assertThat(template407.footerId()).isEqualTo("ID 407 (11/2016)");
  }

  private byte[] pdfWithFooter(String footerText) throws Exception {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.newLineAtOffset(36, 24);
        content.showText(footerText == null ? "" : footerText);
        content.endText();
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private RenderedOcrPage renderedPage(int page) throws Exception {
    BufferedImage image = new BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK);
    graphics.drawRect(20, 40, 200, 220);
    graphics.drawString("Application Type", 30, 70);
    graphics.drawString("ID 988A (06/2024)", 20, 300);
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

  private DocumentTemplate detectActual(TemplateDetectionService service, BaiduOcrPageRenderer renderer, Path file)
      throws Exception {
    byte[] bytes = Files.readAllBytes(file);
    return service.detect(
        "renamed-upload.pdf",
        "application/pdf",
        bytes,
        renderer.render("renamed-upload.pdf", "application/pdf", bytes)
    );
  }

  private Path fileContaining(String value) throws Exception {
    try (var files = Files.list(Path.of("..", "docs", "5.12_full_tests"))) {
      return files
          .filter(file -> file.getFileName().toString().contains(value))
          .filter(file -> file.getFileName().toString().toLowerCase().endsWith(".pdf"))
          .findFirst()
          .orElseThrow();
    }
  }

  private Path fileEndingWith(String value) throws Exception {
    try (var files = Files.list(Path.of("..", "docs", "5.12_full_tests"))) {
      return files
          .filter(file -> file.getFileName().toString().endsWith(value))
          .findFirst()
          .orElseThrow();
    }
  }

  private static final class FakeFieldRegionOcrGateway implements FieldRegionOcrGateway {
    private final List<FieldRegionOcrResult> results;
    private final List<byte[]> requests = new ArrayList<>();

    private FakeFieldRegionOcrGateway(List<FieldRegionOcrResult> results) {
      this.results = List.copyOf(results);
    }

    @Override
    public List<FieldRegionOcrResult> recognizeBatch(List<byte[]> cropImageBytes) {
      requests.addAll(cropImageBytes);
      List<FieldRegionOcrResult> padded = new ArrayList<>();
      for (int index = 0; index < cropImageBytes.size(); index += 1) {
        padded.add(index < results.size() ? results.get(index) : FieldRegionOcrResult.unavailable("missing"));
      }
      return padded;
    }
  }
}
