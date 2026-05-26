package com.aiform.id995a.ocr;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

final class FieldCropper {

  private FieldCropper() {}

  static CropResult crop(RenderedOcrPage page, List<Integer> bbox, CropKind kind) {
    if (page == null || bbox.size() < 4 || page.pngBytes().length == 0) {
      return CropResult.empty();
    }
    try {
      BufferedImage source = ImageIO.read(new ByteArrayInputStream(page.pngBytes()));
      if (source == null) {
        return CropResult.empty();
      }
      List<Integer> expanded = expandBbox(bbox, source.getWidth(), source.getHeight(), kind);
      if (expanded.size() < 4) {
        return CropResult.empty();
      }
      int left = expanded.get(0);
      int top = expanded.get(1);
      int right = expanded.get(2);
      int bottom = expanded.get(3);
      if (right <= left || bottom <= top) {
        return CropResult.empty();
      }
      BufferedImage sourceCrop = source.getSubimage(left, top, right - left, bottom - top);
      BufferedImage analysisRgb = toRgb(sourceCrop);
      BufferedImage rgb = prepareCropImage(sourceCrop, kind);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(rgb, "jpg", output);
      byte[] bytes = output.toByteArray();
      ByteArrayOutputStream analysisOutput = new ByteArrayOutputStream();
      ImageIO.write(analysisRgb, "jpg", analysisOutput);
      return new CropResult(
          bytes,
          "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes),
          analysisOutput.toByteArray(),
          expanded,
          hasDarkInkNearEdge(analysisRgb)
      );
    } catch (IOException | RuntimeException exception) {
      return CropResult.empty();
    }
  }

  static List<Integer> expandBbox(List<Integer> bbox, int imageWidth, int imageHeight, CropKind kind) {
    if (bbox.size() < 4) {
      return List.of();
    }
    int left = bbox.get(0);
    int top = bbox.get(1);
    int right = bbox.get(2);
    int bottom = bbox.get(3);
    int width = Math.max(1, right - left);
    int height = Math.max(1, bottom - top);
    Padding padding = padding(kind, width, height, imageWidth, imageHeight);
    return clampBbox(
        List.of(left - padding.left(), top - padding.top(), right + padding.right(), bottom + padding.bottom()),
        imageWidth,
        imageHeight
    );
  }

  private static Padding padding(CropKind kind, int width, int height, int imageWidth, int imageHeight) {
    int baseX = Math.max(14, Math.round(height * 0.75f));
    int baseY = Math.max(8, Math.round(height * 0.22f));
    return switch (kind) {
      case ADDRESS -> new Padding(
          Math.max(baseX, Math.max(Math.round(width * 0.10f), Math.round(imageWidth * 0.018f))),
          addressVerticalPadding(height, imageHeight, false),
          Math.max(baseX, Math.max(Math.round(width * 0.36f), Math.round(imageWidth * 0.060f))),
          addressVerticalPadding(height, imageHeight, true)
      );
      case ADDRESS_WIDE -> new Padding(
          Math.max(baseX, Math.max(Math.round(width * 0.90f), Math.round(imageWidth * 0.350f))),
          Math.max(baseY, Math.max(Math.round(height * 0.85f), Math.round(imageHeight * 0.012f))),
          Math.max(baseX, Math.max(Math.round(width * 0.72f), Math.round(imageWidth * 0.120f))),
          Math.max(baseY, Math.max(Math.round(height * 5.50f), Math.round(imageHeight * 0.120f)))
      );
      case LONG_TEXT -> new Padding(
          Math.max(baseX, Math.max(Math.round(width * 0.10f), Math.round(imageWidth * 0.018f))),
          Math.max(baseY, Math.round(height * 0.30f)),
          Math.max(baseX, Math.max(Math.round(width * 0.18f), Math.round(imageWidth * 0.030f))),
          Math.max(baseY, Math.round(height * 0.30f))
      );
      case EMAIL, SYMBOL -> new Padding(
          Math.max(baseX, Math.round(width * 0.08f)),
          baseY,
          Math.max(baseX, Math.round(width * 0.14f)),
          baseY
      );
      case SERIAL -> new Padding(
          Math.max(32, Math.max(Math.round(width * 0.32f), Math.round(imageWidth * 0.045f))),
          Math.max(12, Math.round(height * 0.90f)),
          Math.max(44, Math.max(Math.round(width * 0.50f), Math.round(imageWidth * 0.070f))),
          Math.max(12, Math.round(height * 0.90f))
      );
      case SIGNATURE -> new Padding(
          Math.max(baseX, Math.round(width * 0.16f)),
          Math.max(baseY, Math.round(height * 0.35f)),
          Math.max(baseX, Math.round(width * 0.18f)),
          Math.max(baseY, Math.round(height * 0.35f))
      );
      case SELECTION -> new Padding(
          Math.max(10, Math.round(height * 0.35f)),
          Math.max(8, Math.round(height * 0.35f)),
          Math.max(18, Math.round(height * 0.60f)),
          Math.max(8, Math.round(height * 0.35f))
      );
      case SNAPSHOT -> new Padding(
          Math.max(10, Math.round(height * 0.35f)),
          Math.max(6, Math.round(height * 0.18f)),
          Math.max(14, Math.round(height * 0.50f)),
          Math.max(6, Math.round(height * 0.18f))
      );
      case WIDE_RETRY -> new Padding(
          Math.max(28, Math.max(Math.round(width * 0.22f), Math.round(imageWidth * 0.035f))),
          Math.max(10, Math.round(height * 0.38f)),
          Math.max(36, Math.max(Math.round(width * 0.32f), Math.round(imageWidth * 0.055f))),
          Math.max(10, Math.round(height * 0.38f))
      );
    };
  }

  private static int addressVerticalPadding(int height, int imageHeight, boolean bottom) {
    int base = Math.max(8, Math.round(height * 0.30f));
    boolean likelySingleLine = height <= Math.max(70, Math.round(imageHeight * 0.035f));
    float multiplier = likelySingleLine
        ? (bottom ? 3.80f : 0.75f)
        : (bottom ? 0.45f : 0.25f);
    int requested = Math.max(base, Math.round(height * multiplier));
    int cap = Math.max(24, Math.round(imageHeight * (likelySingleLine ? 0.140f : 0.055f)));
    return Math.min(requested, cap);
  }

  private static BufferedImage prepareCropImage(BufferedImage sourceCrop, CropKind kind) {
    int width = sourceCrop.getWidth();
    int height = sourceCrop.getHeight();
    int minimumHeight = minimumReviewHeight(kind);
    double scale = 1.0;
    if (minimumHeight > 0 && height < minimumHeight) {
      scale = minimumHeight / (double) Math.max(1, height);
    }
    scale = Math.min(scale, 4.0);
    int maxDimension = 3200;
    if (width * scale > maxDimension) {
      scale = Math.min(scale, maxDimension / (double) Math.max(1, width));
    }
    if (height * scale > maxDimension) {
      scale = Math.min(scale, maxDimension / (double) Math.max(1, height));
    }
    int targetWidth = Math.max(1, (int) Math.round(width * scale));
    int targetHeight = Math.max(1, (int) Math.round(height * scale));
    BufferedImage rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = rgb.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.drawImage(sourceCrop, 0, 0, targetWidth, targetHeight, null);
    graphics.dispose();
    return rgb;
  }

  private static BufferedImage toRgb(BufferedImage source) {
    BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = rgb.createGraphics();
    graphics.drawImage(source, 0, 0, null);
    graphics.dispose();
    return rgb;
  }

  private static int minimumReviewHeight(CropKind kind) {
    return switch (kind) {
      case ADDRESS, WIDE_RETRY -> 240;
      case ADDRESS_WIDE, SERIAL -> 320;
      case EMAIL, SYMBOL -> 220;
      case LONG_TEXT, SIGNATURE -> 200;
      case SELECTION -> 180;
      case SNAPSHOT -> 0;
    };
  }

  private static List<Integer> clampBbox(List<Integer> bbox, int imageWidth, int imageHeight) {
    int left = Math.max(0, Math.min(imageWidth, bbox.get(0)));
    int top = Math.max(0, Math.min(imageHeight, bbox.get(1)));
    int right = Math.max(0, Math.min(imageWidth, bbox.get(2)));
    int bottom = Math.max(0, Math.min(imageHeight, bbox.get(3)));
    if (right <= left || bottom <= top) {
      return List.of();
    }
    return List.of(left, top, right, bottom);
  }

  private static boolean hasDarkInkNearEdge(BufferedImage image) {
    if (image == null || image.getWidth() < 8 || image.getHeight() < 8) {
      return false;
    }
    int edge = Math.max(3, Math.round(Math.min(image.getWidth(), image.getHeight()) * 0.04f));
    int rightInk = darkPixelCount(image, image.getWidth() - edge, 0, image.getWidth(), image.getHeight());
    int leftInk = darkPixelCount(image, 0, 0, edge, image.getHeight());
    int bottomInk = darkPixelCount(image, 0, image.getHeight() - edge, image.getWidth(), image.getHeight());
    int threshold = Math.max(3, edge * 2);
    return rightInk >= threshold || leftInk >= threshold || bottomInk >= threshold;
  }

  private static int darkPixelCount(BufferedImage image, int left, int top, int right, int bottom) {
    int count = 0;
    for (int y = Math.max(0, top); y < Math.min(image.getHeight(), bottom); y += 1) {
      for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x += 1) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        if ((r + g + b) / 3 < 150) {
          count += 1;
        }
      }
    }
    return count;
  }

  enum CropKind {
    ADDRESS,
    ADDRESS_WIDE,
    EMAIL,
    LONG_TEXT,
    SELECTION,
    SERIAL,
    SIGNATURE,
    SNAPSHOT,
    SYMBOL,
    WIDE_RETRY
  }

  record CropResult(byte[] bytes, String dataUrl, byte[] analysisBytes, List<Integer> bbox, boolean inkNearEdge) {
    private static CropResult empty() {
      return new CropResult(new byte[0], "", new byte[0], List.of(), false);
    }
  }

  private record Padding(int left, int top, int right, int bottom) {}
}
