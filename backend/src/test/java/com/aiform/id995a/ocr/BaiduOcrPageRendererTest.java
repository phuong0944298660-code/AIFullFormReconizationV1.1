package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class BaiduOcrPageRendererTest {

  @Test
  void rendersJbig2ScannedPdfPagesWithVisibleContent() throws Exception {
    Path samplePdf = findSamplePdf();

    BaiduOcrPageRenderer renderer = new BaiduOcrPageRenderer(120);
    List<RenderedOcrPage> pages = renderer.render(
        samplePdf.getFileName().toString(),
        "application/pdf",
        Files.readAllBytes(samplePdf)
    );

    assertThat(pages).hasSize(5);
    assertThat(nonWhiteRatio(pages.get(0).pngBytes())).isGreaterThan(0.01);
  }

  @Test
  void usesConservativeDefaultDpiWhenLongSideLimitIsDisabled() throws Exception {
    BaiduOcrPageRenderer renderer = new BaiduOcrPageRenderer(BaiduOcrPageRenderer.DEFAULT_RENDER_DPI, 0);

    List<RenderedOcrPage> pages = renderer.render(
        "letter.pdf",
        "application/pdf",
        onePagePdf(PDRectangle.LETTER)
    );

    assertThat(pages).hasSize(1);
    assertThat(pages.get(0).imageWidth()).isEqualTo(2040);
    assertThat(pages.get(0).imageHeight()).isBetween(2639, 2640);
  }

  @Test
  void keepsPdfAtConfiguredThreeHundredDpiWhenLongSideLimitIsDisabled() throws Exception {
    BaiduOcrPageRenderer renderer = new BaiduOcrPageRenderer(300, 0);

    List<RenderedOcrPage> pages = renderer.render(
        "letter.pdf",
        "application/pdf",
        onePagePdf(PDRectangle.LETTER)
    );

    assertThat(pages).hasSize(1);
    assertThat(pages.get(0).imageWidth()).isEqualTo(2550);
    assertThat(pages.get(0).imageHeight()).isBetween(3299, 3300);
  }

  private Path findSamplePdf() throws Exception {
    Path samplesDir = Path.of("..", "docs", "5.12_full_tests");
    try (Stream<Path> files = Files.list(samplesDir)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith("-A-V8.pdf"))
          .findFirst()
          .orElseThrow();
    }
  }

  private byte[] onePagePdf(PDRectangle pageSize) throws Exception {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage(pageSize));
      document.save(output);
      return output.toByteArray();
    }
  }

  private double nonWhiteRatio(byte[] pngBytes) throws Exception {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
    assertThat(image).isNotNull();
    int nonWhite = 0;
    int total = image.getWidth() * image.getHeight();
    for (int y = 0; y < image.getHeight(); y += 1) {
      for (int x = 0; x < image.getWidth(); x += 1) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        if (red < 245 || green < 245 || blue < 245) {
          nonWhite += 1;
        }
      }
    }
    return (double) nonWhite / Math.max(1, total);
  }
}
