package com.aiform.id995a.ocr;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BaiduOcrPageRenderer {

  public static final float DEFAULT_RENDER_DPI = 240f;

  private final float renderDpi;
  private final int maxImageLongSide;

  @Autowired
  public BaiduOcrPageRenderer(
      @Value("${ocr.baidu.pdf-dpi:240}") float renderDpi,
      @Value("${ocr.baidu.max-image-long-side:0}") int maxImageLongSide
  ) {
    this.renderDpi = Math.max(120f, renderDpi);
    this.maxImageLongSide = maxImageLongSide <= 0 ? 0 : Math.max(900, maxImageLongSide);
  }

  public BaiduOcrPageRenderer(float renderDpi) {
    this(renderDpi, 0);
  }

  public List<RenderedOcrPage> render(String filename, String contentType, byte[] fileBytes) throws IOException {
    if (isImage(filename, contentType)) {
      return List.of(renderImage(fileBytes));
    }
    return renderPdf(fileBytes);
  }

  private List<RenderedOcrPage> renderPdf(byte[] pdfBytes) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdfBytes)) {
      PDFRenderer renderer = new PDFRenderer(document);
      List<RenderedOcrPage> pages = new ArrayList<>();
      for (int index = 0; index < document.getNumberOfPages(); index += 1) {
        BufferedImage image = renderer.renderImageWithDPI(index, renderDpi, ImageType.RGB);
        pages.add(toRenderedPage(index + 1, image));
      }
      return List.copyOf(pages);
    }
  }

  private RenderedOcrPage renderImage(byte[] imageBytes) throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
    if (image == null) {
      throw new IOException("Uploaded image could not be decoded.");
    }
    return toRenderedPage(1, image);
  }

  private RenderedOcrPage toRenderedPage(int page, BufferedImage image) throws IOException {
    BufferedImage normalized = resizeIfNeeded(image);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String format = shouldUseJpeg(normalized) ? "jpg" : "png";
    ImageIO.write(toRgb(normalized), format, output);
    byte[] imageBytes = output.toByteArray();
    String mimeType = "jpg".equals(format) ? "image/jpeg" : "image/png";
    String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
    return new RenderedOcrPage(page, imageBytes, dataUrl, normalized.getWidth(), normalized.getHeight());
  }

  private BufferedImage resizeIfNeeded(BufferedImage image) {
    int longSide = Math.max(image.getWidth(), image.getHeight());
    if (maxImageLongSide <= 0) {
      return image;
    }
    if (longSide <= maxImageLongSide) {
      return image;
    }
    double scale = (double) maxImageLongSide / longSide;
    int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
    BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = resized.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.drawImage(image, 0, 0, width, height, null);
    graphics.dispose();
    return resized;
  }

  private boolean shouldUseJpeg(BufferedImage image) {
    return Math.max(image.getWidth(), image.getHeight()) >= 900;
  }

  private BufferedImage toRgb(BufferedImage image) {
    if (image.getType() == BufferedImage.TYPE_INT_RGB) {
      return image;
    }
    BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = rgb.createGraphics();
    graphics.drawImage(image, 0, 0, null);
    graphics.dispose();
    return rgb;
  }

  private boolean isImage(String filename, String contentType) {
    String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String lowerFilename = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    return lowerContentType.startsWith("image/")
        || lowerFilename.endsWith(".png")
        || lowerFilename.endsWith(".jpg")
        || lowerFilename.endsWith(".jpeg")
        || lowerFilename.endsWith(".webp")
        || lowerFilename.endsWith(".bmp");
  }
}
