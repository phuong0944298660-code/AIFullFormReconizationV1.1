package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
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

  private Path findSamplePdf() throws Exception {
    Path samplesDir = Path.of("..", "docs", "5.12_full_tests");
    try (Stream<Path> files = Files.list(samplesDir)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith("-A-V8.pdf"))
          .findFirst()
          .orElseThrow();
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
