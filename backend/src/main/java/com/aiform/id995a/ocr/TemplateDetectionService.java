package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class TemplateDetectionService {

  private static final Pattern FOOTER_ID_PATTERN = Pattern.compile(
      "(?i)\\bI\\s*D\\s*([0-9]{3,4}\\s*[A-Z]?)\\s*(?:\\(|\\[)?\\s*([0-9]{2})\\s*/\\s*([0-9]{4})\\s*(?:\\)|\\])?"
  );
  private static final FooterId FOOTER_988A = new FooterId("988A", "06", "2024", "ID 988A (06/2024)");
  private static final FooterId FOOTER_988B = new FooterId("988B", "06", "2024", "ID 988B (06/2024)");
  private static final FooterId FOOTER_407 = new FooterId("407", "11", "2016", "ID 407 (11/2016)");

  private final FieldRegionOcrGateway fieldRegionOcrGateway;
  @SuppressWarnings("unused")
  private final ObjectMapper objectMapper;

  public TemplateDetectionService(FieldRegionOcrGateway fieldRegionOcrGateway, ObjectMapper objectMapper) {
    this.fieldRegionOcrGateway = fieldRegionOcrGateway;
    this.objectMapper = objectMapper;
  }

  public DocumentTemplate detect(
      String filename,
      String contentType,
      byte[] fileBytes,
      List<RenderedOcrPage> pages
  ) {
    List<RenderedOcrPage> safePages = pages == null ? List.of() : pages;
    String structureHash = structureHash(safePages);
    FooterId pdfFooter = findFooterId(extractPdfText(filename, contentType, fileBytes));
    if (pdfFooter != null) {
      return templateFromFooter(pdfFooter, safePages.size(), 98, "pdf_text_footer", structureHash);
    }

    DocumentTemplate visualTemplate = visualTemplate(safePages, structureHash);
    if (visualTemplate != null) {
      return visualTemplate;
    }
    FooterId ocrFooter = findFooterId(footerCropText(safePages));
    if (ocrFooter != null) {
      return templateFromFooter(ocrFooter, safePages.size(), 96, "footer_ocr", structureHash);
    }
    return DocumentTemplate.unknown(safePages.size(), structureHash);
  }

  private DocumentTemplate templateFromFooter(
      FooterId footerId,
      int pageCount,
      double confidence,
      String source,
      String structureHash
  ) {
    String form = footerId.formId().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    String templateId = "id" + form + "_" + footerId.year() + "_" + footerId.month();
    return new DocumentTemplate(templateId, footerId.display(), pageCount, confidence, source, structureHash);
  }

  private FooterId findFooterId(String text) {
    String normalized = normalizeFooterText(text);
    if (normalized.isBlank()) {
      return null;
    }
    Matcher matcher = FOOTER_ID_PATTERN.matcher(normalized);
    if (!matcher.find()) {
      return null;
    }
    String formId = matcher.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    String month = matcher.group(2);
    String year = matcher.group(3);
    return new FooterId(formId, month, year, "ID " + formId + " (" + month + "/" + year + ")");
  }

  private String normalizeFooterText(String text) {
    return text == null ? "" : text
        .replace('（', '(')
        .replace('）', ')')
        .replace('／', '/')
        .replaceAll("[\\r\\n]+", " ")
        .trim();
  }

  private String extractPdfText(String filename, String contentType, byte[] fileBytes) {
    if (fileBytes == null || fileBytes.length == 0 || !isPdf(filename, contentType)) {
      return "";
    }
    try (PDDocument document = Loader.loadPDF(fileBytes)) {
      PDFTextStripper stripper = new PDFTextStripper();
      StringBuilder text = new StringBuilder();
      int pages = document.getNumberOfPages();
      for (int page = 1; page <= pages; page += 1) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        text.append(stripper.getText(document)).append('\n');
      }
      return text.toString();
    } catch (IOException | RuntimeException exception) {
      return "";
    }
  }

  private boolean isPdf(String filename, String contentType) {
    String lowerContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    String lowerFilename = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    return lowerContentType.contains("pdf") || lowerFilename.endsWith(".pdf");
  }

  private String footerCropText(List<RenderedOcrPage> pages) {
    List<byte[]> crops = new ArrayList<>();
    for (RenderedOcrPage page : pages) {
      byte[] crop = footerCrop(page);
      if (crop.length > 0) {
        crops.add(crop);
      }
    }
    if (crops.isEmpty()) {
      return "";
    }
    try {
      StringBuilder text = new StringBuilder();
      for (FieldRegionOcrResult result : fieldRegionOcrGateway.recognizeBatch(crops)) {
        if (result != null && result.confidence() >= 40 && !result.text().isBlank()) {
          text.append(result.text()).append('\n');
        }
      }
      return text.toString();
    } catch (IOException | RuntimeException exception) {
      return "";
    }
  }

  private byte[] footerCrop(RenderedOcrPage page) {
    if (page == null || page.pngBytes() == null || page.pngBytes().length == 0) {
      return new byte[0];
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
      if (image == null || image.getWidth() < 10 || image.getHeight() < 10) {
        return new byte[0];
      }
      int left = 0;
      int top = Math.max(0, Math.round(image.getHeight() * 0.86f));
      int width = Math.max(1, Math.round(image.getWidth() * 0.45f));
      int height = image.getHeight() - top;
      BufferedImage crop = image.getSubimage(left, top, width, height);
      BufferedImage prepared = scaleForFooterOcr(crop);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(prepared, "jpg", output);
      return output.toByteArray();
    } catch (IOException | RuntimeException exception) {
      return new byte[0];
    }
  }

  private DocumentTemplate visualTemplate(List<RenderedOcrPage> pages, String structureHash) {
    if (pages.isEmpty()) {
      return null;
    }
    BufferedImage firstPage = readPageImage(pages.get(0));
    if (firstPage == null) {
      return null;
    }

    boolean officialUseBox = hasOfficialUseBox(firstPage);
    boolean applicationTypeTable = hasApplicationTypeTable(firstPage);
    boolean footerIdentifierInk = hasFooterIdentifierInk(firstPage);
    int pageCount = pages.size();

    if (officialUseBox && applicationTypeTable && pageCount >= 4) {
      return templateFromFooter(FOOTER_988A, pageCount, 92, "visual_layout_footer", structureHash);
    }
    if (officialUseBox && footerIdentifierInk && pageCount >= 3 && pageCount <= 4) {
      return templateFromFooter(FOOTER_988B, pageCount, 90, "visual_layout_footer", structureHash);
    }
    if (!officialUseBox && footerIdentifierInk && pageCount == 4) {
      return templateFromFooter(FOOTER_407, pageCount, 90, "visual_layout_footer", structureHash);
    }
    return null;
  }

  private BufferedImage readPageImage(RenderedOcrPage page) {
    if (page == null || page.pngBytes() == null || page.pngBytes().length == 0) {
      return null;
    }
    try {
      return ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
    } catch (IOException | RuntimeException exception) {
      return null;
    }
  }

  private boolean hasOfficialUseBox(BufferedImage image) {
    double top = bestHorizontalLineCoverage(image, 0.62, 0.96, 0.02, 0.08);
    double bottom = bestHorizontalLineCoverage(image, 0.62, 0.96, 0.13, 0.22);
    double left = bestVerticalLineCoverage(image, 0.60, 0.70, 0.02, 0.22);
    double right = bestVerticalLineCoverage(image, 0.92, 0.98, 0.02, 0.22);
    return top >= 0.58 && bottom >= 0.58 && left >= 0.46 && right >= 0.46;
  }

  private boolean hasApplicationTypeTable(BufferedImage image) {
    double leftRightSplitter = bestVerticalLineCoverage(image, 0.69, 0.77, 0.28, 0.52);
    double topLine = bestHorizontalLineCoverage(image, 0.04, 0.96, 0.27, 0.32);
    double bottomLine = bestHorizontalLineCoverage(image, 0.04, 0.96, 0.48, 0.53);
    return leftRightSplitter >= 0.58 && topLine >= 0.72 && bottomLine >= 0.72;
  }

  private boolean hasFooterIdentifierInk(BufferedImage image) {
    int left = ratioToX(image, 0.01);
    int right = ratioToX(image, 0.27);
    int top = ratioToY(image, 0.91);
    int bottom = ratioToY(image, 0.99);
    int dark = 0;
    int total = 0;
    int columnsWithInk = 0;
    for (int x = left; x < right; x += 1) {
      int columnDark = 0;
      for (int y = top; y < bottom; y += 1) {
        if (isDark(image.getRGB(x, y))) {
          dark += 1;
          columnDark += 1;
        }
        total += 1;
      }
      if (columnDark >= Math.max(1, (bottom - top) / 25)) {
        columnsWithInk += 1;
      }
    }
    double darkRatio = dark / (double) Math.max(1, total);
    double columnRatio = columnsWithInk / (double) Math.max(1, right - left);
    return darkRatio >= 0.006 && columnRatio >= 0.08;
  }

  private double bestHorizontalLineCoverage(
      BufferedImage image,
      double xStart,
      double xEnd,
      double yStart,
      double yEnd
  ) {
    int left = ratioToX(image, xStart);
    int right = ratioToX(image, xEnd);
    int top = ratioToY(image, yStart);
    int bottom = Math.max(top + 1, ratioToY(image, yEnd));
    double best = 0;
    for (int y = top; y < bottom; y += 1) {
      best = Math.max(best, horizontalLineCoverageAt(image, y, left, right));
    }
    return best;
  }

  private double bestVerticalLineCoverage(
      BufferedImage image,
      double xStart,
      double xEnd,
      double yStart,
      double yEnd
  ) {
    int left = ratioToX(image, xStart);
    int right = Math.max(left + 1, ratioToX(image, xEnd));
    int top = ratioToY(image, yStart);
    int bottom = ratioToY(image, yEnd);
    double best = 0;
    for (int x = left; x < right; x += 1) {
      best = Math.max(best, verticalLineCoverageAt(image, x, top, bottom));
    }
    return best;
  }

  private double horizontalLineCoverageAt(BufferedImage image, int y, int left, int right) {
    int band = Math.max(1, Math.round(image.getHeight() * 0.0012f));
    int columnsWithInk = 0;
    for (int x = left; x < right; x += 1) {
      boolean found = false;
      for (int yy = Math.max(0, y - band); yy <= Math.min(image.getHeight() - 1, y + band); yy += 1) {
        if (isDark(image.getRGB(x, yy))) {
          found = true;
          break;
        }
      }
      if (found) {
        columnsWithInk += 1;
      }
    }
    return columnsWithInk / (double) Math.max(1, right - left);
  }

  private double verticalLineCoverageAt(BufferedImage image, int x, int top, int bottom) {
    int band = Math.max(1, Math.round(image.getWidth() * 0.0012f));
    int rowsWithInk = 0;
    for (int y = top; y < bottom; y += 1) {
      boolean found = false;
      for (int xx = Math.max(0, x - band); xx <= Math.min(image.getWidth() - 1, x + band); xx += 1) {
        if (isDark(image.getRGB(xx, y))) {
          found = true;
          break;
        }
      }
      if (found) {
        rowsWithInk += 1;
      }
    }
    return rowsWithInk / (double) Math.max(1, bottom - top);
  }

  private int ratioToX(BufferedImage image, double ratio) {
    return Math.min(image.getWidth() - 1, Math.max(0, (int) Math.round(image.getWidth() * ratio)));
  }

  private int ratioToY(BufferedImage image, double ratio) {
    return Math.min(image.getHeight() - 1, Math.max(0, (int) Math.round(image.getHeight() * ratio)));
  }

  private boolean isDark(int rgb) {
    int red = (rgb >> 16) & 0xff;
    int green = (rgb >> 8) & 0xff;
    int blue = rgb & 0xff;
    return (red + green + blue) / 3 < 155;
  }

  private BufferedImage scaleForFooterOcr(BufferedImage image) {
    int minimumHeight = 220;
    double scale = image.getHeight() >= minimumHeight ? 1.0 : minimumHeight / (double) Math.max(1, image.getHeight());
    scale = Math.min(scale, 4.0);
    int targetWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
    int targetHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
    BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = scaled.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
    graphics.dispose();
    return scaled;
  }

  private String structureHash(List<RenderedOcrPage> pages) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(("pages:" + pages.size()).getBytes(StandardCharsets.UTF_8));
      for (RenderedOcrPage page : pages.stream().limit(2).toList()) {
        digest.update(coarsePageSignature(page).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    } catch (NoSuchAlgorithmException exception) {
      return "unknown";
    }
  }

  private String coarsePageSignature(RenderedOcrPage page) {
    if (page == null || page.pngBytes() == null || page.pngBytes().length == 0) {
      return "blank";
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
      if (image == null) {
        return "blank";
      }
      int grid = 24;
      StringBuilder signature = new StringBuilder();
      signature.append(page.page()).append(':').append(image.getWidth()).append('x').append(image.getHeight()).append(':');
      for (int gy = 0; gy < grid; gy += 1) {
        for (int gx = 0; gx < grid; gx += 1) {
          int left = gx * image.getWidth() / grid;
          int right = Math.max(left + 1, (gx + 1) * image.getWidth() / grid);
          int top = gy * image.getHeight() / grid;
          int bottom = Math.max(top + 1, (gy + 1) * image.getHeight() / grid);
          signature.append(darkRatioBucket(image, left, top, right, bottom));
        }
      }
      return signature.toString();
    } catch (IOException | RuntimeException exception) {
      return "blank";
    }
  }

  private char darkRatioBucket(BufferedImage image, int left, int top, int right, int bottom) {
    int dark = 0;
    int total = 0;
    for (int y = top; y < bottom; y += 1) {
      for (int x = left; x < right; x += 1) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        if ((red + green + blue) / 3 < 205) {
          dark += 1;
        }
        total += 1;
      }
    }
    double ratio = dark / (double) Math.max(1, total);
    if (ratio >= 0.18) {
      return '3';
    }
    if (ratio >= 0.08) {
      return '2';
    }
    if (ratio >= 0.02) {
      return '1';
    }
    return '0';
  }

  private record FooterId(String formId, String month, String year, String display) {}
}
