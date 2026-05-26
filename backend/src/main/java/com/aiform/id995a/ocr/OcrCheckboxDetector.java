package com.aiform.id995a.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

public class OcrCheckboxDetector {

  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

  public DetectionResult detectPage(String imageDataUrl, List<OcrTextBlock> blocks) {
    BufferedImage image = decodeImage(imageDataUrl);
    if (image == null) {
      return new DetectionResult(0, 0, List.of());
    }
    return new DetectionResult(image.getWidth(), image.getHeight(), detect(image, blocks));
  }

  public List<OcrCheckbox> detect(BufferedImage image, List<OcrTextBlock> blocks) {
    int width = image.getWidth();
    int height = image.getHeight();
    byte[] dark = buildDarkMap(image);
    byte[] visited = new byte[width * height];
    int[] queue = new int[width * height];
    List<Candidate> componentCandidates = new ArrayList<>();

    for (int y = 0; y < height; y += 1) {
      for (int x = 0; x < width; x += 1) {
        int index = y * width + x;
        if (dark[index] == 0 || visited[index] != 0) {
          continue;
        }
        Component component = floodFill(index, width, height, dark, visited, queue);
        if (isCheckboxLike(component, width, height, dark)) {
          componentCandidates.add(toCandidate(component, width, height, dark));
        }
      }
    }
    List<Candidate> candidates = new ArrayList<>(componentCandidates);
    candidates.addAll(filterScanCandidates(scanBoxWindows(width, height, dark), componentCandidates));

    List<Candidate> merged = mergeCloseCandidates(candidates);
    List<OcrCheckbox> checkboxes = new ArrayList<>();
    int checkboxIndex = 1;
    boolean tableFilterEnabled = hasFillableTableBlocks(blocks, width, height);
    for (Candidate candidate : merged) {
      if (tableFilterEnabled && !isInsideFillableTable(candidate, blocks, width, height)) {
        continue;
      }
      String label = bindLabel(candidate, blocks);
      checkboxes.add(new OcrCheckbox(
          checkboxIndex,
          candidate.checked(),
          candidate.confidence(),
          label,
          candidate.bbox()
      ));
      checkboxIndex += 1;
    }
    return List.copyOf(checkboxes);
  }

  private byte[] buildDarkMap(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    byte[] dark = new byte[width * height];
    for (int y = 0; y < height; y += 1) {
      for (int x = 0; x < width; x += 1) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
        if (luminance < 170) {
          dark[y * width + x] = 1;
        }
      }
    }
    return dark;
  }

  private Component floodFill(
      int start,
      int width,
      int height,
      byte[] dark,
      byte[] visited,
      int[] queue
  ) {
    int head = 0;
    int tail = 0;
    queue[tail++] = start;
    visited[start] = 1;
    int minX = start % width;
    int maxX = minX;
    int minY = start / width;
    int maxY = minY;
    int pixels = 0;

    while (head < tail) {
      int current = queue[head++];
      int x = current % width;
      int y = current / width;
      pixels += 1;
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);

      if (x > 0) {
        tail = enqueue(current - 1, dark, visited, queue, tail);
      }
      if (x + 1 < width) {
        tail = enqueue(current + 1, dark, visited, queue, tail);
      }
      if (y > 0) {
        tail = enqueue(current - width, dark, visited, queue, tail);
      }
      if (y + 1 < height) {
        tail = enqueue(current + width, dark, visited, queue, tail);
      }
    }

    return new Component(minX, minY, maxX + 1, maxY + 1, pixels);
  }

  private int enqueue(int index, byte[] dark, byte[] visited, int[] queue, int tail) {
    if (dark[index] != 0 && visited[index] == 0) {
      visited[index] = 1;
      queue[tail++] = index;
    }
    return tail;
  }

  private boolean isCheckboxLike(Component component, int imageWidth, int imageHeight, byte[] dark) {
    int width = component.width();
    int height = component.height();
    int minDimension = Math.min(width, height);
    int maxDimension = Math.max(width, height);
    int maxBoxSize = Math.max(28, Math.min(78, Math.round(Math.min(imageWidth, imageHeight) * 0.045f)));
    if (minDimension < 15 || maxDimension > maxBoxSize) {
      return false;
    }
    double ratio = (double) width / Math.max(1, height);
    if (ratio < 0.65 || ratio > 1.45) {
      return false;
    }
    double density = (double) component.pixels() / Math.max(1, width * height);
    if (density < 0.08 || density > 0.72) {
      return false;
    }
    if (innerDarkRatio(component, imageWidth, dark) > 0.30) {
      return false;
    }

    EdgeSupport edges = edgeSupport(component, imageWidth, dark);
    return edges.top() >= 0.35
        && edges.bottom() >= 0.35
        && edges.left() >= 0.35
        && edges.right() >= 0.35;
  }

  private Candidate toCandidate(Component component, int imageWidth, int imageHeight, byte[] dark) {
    int pad = Math.max(2, Math.round(Math.min(component.width(), component.height()) * 0.22f));
    int x0 = clamp(component.x0() + pad, component.x0(), component.x1());
    int y0 = clamp(component.y0() + pad, component.y0(), component.y1());
    int x1 = clamp(component.x1() - pad, x0, component.x1());
    int y1 = clamp(component.y1() - pad, y0, component.y1());
    int innerArea = Math.max(1, (x1 - x0) * (y1 - y0));
    int innerDark = 0;
    for (int y = y0; y < y1; y += 1) {
      for (int x = x0; x < x1; x += 1) {
        if (dark[y * imageWidth + x] != 0) {
          innerDark += 1;
        }
      }
    }

    double innerRatio = (double) innerDark / innerArea;
    int minDimension = Math.min(component.width(), component.height());
    boolean checked = innerRatio >= 0.035 || innerDark >= Math.max(8, Math.round(minDimension * 0.42f));
    double confidence = checked
        ? clampDouble(0.55 + innerRatio * 3.2, 0.55, 0.99)
        : clampDouble(0.72 - innerRatio * 1.8, 0.35, 0.92);
    return new Candidate(
        List.of(component.x0(), component.y0(), component.x1(), component.y1()),
        checked,
        confidence
    );
  }

  private List<Candidate> scanBoxWindows(int imageWidth, int imageHeight, byte[] dark) {
    int[] integral = buildIntegral(imageWidth, imageHeight, dark);
    int minSize = Math.max(16, Math.round(Math.min(imageWidth, imageHeight) * 0.012f));
    int maxSize = Math.min(38, Math.max(26, Math.round(Math.min(imageWidth, imageHeight) * 0.033f)));
    List<Candidate> candidates = new ArrayList<>();
    for (int size = minSize; size <= maxSize; size += 2) {
      int band = Math.max(2, Math.round(size * 0.14f));
      int centerPad = Math.max(3, Math.round(size * 0.24f));
      for (int y = 0; y <= imageHeight - size; y += 2) {
        for (int x = 0; x <= imageWidth - size; x += 2) {
          double top = integralRatio(integral, imageWidth, x, y, x + size, y + band);
          double bottom = integralRatio(integral, imageWidth, x, y + size - band, x + size, y + size);
          double left = integralRatio(integral, imageWidth, x, y, x + band, y + size);
          double right = integralRatio(integral, imageWidth, x + size - band, y, x + size, y + size);
          double edgeMin = Math.min(Math.min(top, bottom), Math.min(left, right));
          if (edgeMin < 0.25) {
            continue;
          }
          if (!hasHorizontalRun(dark, imageWidth, x, y, size, band)
              || !hasHorizontalRun(dark, imageWidth, x, y + size - band, size, band)
              || !hasVerticalRun(dark, imageWidth, x, y, size, band)
              || !hasVerticalRun(dark, imageWidth, x + size - band, y, size, band)) {
            continue;
          }
          double extension = outsideExtensionRatio(integral, imageWidth, imageHeight, x, y, size, band);
          if (extension > 0.80) {
            continue;
          }
          int cx0 = x + centerPad;
          int cy0 = y + centerPad;
          int cx1 = x + size - centerPad;
          int cy1 = y + size - centerPad;
          double centerRatio = integralRatio(integral, imageWidth, cx0, cy0, cx1, cy1);
          if (centerRatio > 0.45) {
            continue;
          }
          boolean checked = centerRatio >= 0.035 || hasDiagonalStroke(integral, imageWidth, x, y, size);
          double confidence = checked
              ? clampDouble(0.64 + centerRatio * 2.4 + edgeMin * 0.2, 0.6, 0.98)
              : clampDouble(0.68 + edgeMin * 0.18 - centerRatio, 0.45, 0.9);
          candidates.add(new Candidate(List.of(x, y, x + size, y + size), checked, confidence));
        }
      }
    }
    return candidates;
  }

  private List<Candidate> filterScanCandidates(List<Candidate> scanned, List<Candidate> anchors) {
    if (anchors.isEmpty()) {
      return scanned;
    }
    return scanned.stream()
        .filter(candidate -> anchors.stream().anyMatch(anchor -> isNearCheckboxColumn(candidate, anchor)))
        .toList();
  }

  private boolean isNearCheckboxColumn(Candidate candidate, Candidate anchor) {
    double candidateCenterX = (candidate.bbox().get(0) + candidate.bbox().get(2)) / 2.0;
    double candidateCenterY = (candidate.bbox().get(1) + candidate.bbox().get(3)) / 2.0;
    double anchorCenterX = (anchor.bbox().get(0) + anchor.bbox().get(2)) / 2.0;
    double anchorCenterY = (anchor.bbox().get(1) + anchor.bbox().get(3)) / 2.0;
    double anchorSize = Math.max(anchor.bbox().get(2) - anchor.bbox().get(0), anchor.bbox().get(3) - anchor.bbox().get(1));
    return Math.abs(candidateCenterX - anchorCenterX) <= Math.max(18, anchorSize * 0.9)
        && Math.abs(candidateCenterY - anchorCenterY) <= 240;
  }

  private int[] buildIntegral(int imageWidth, int imageHeight, byte[] dark) {
    int[] integral = new int[(imageWidth + 1) * (imageHeight + 1)];
    int stride = imageWidth + 1;
    for (int y = 1; y <= imageHeight; y += 1) {
      int row = 0;
      for (int x = 1; x <= imageWidth; x += 1) {
        row += dark[(y - 1) * imageWidth + (x - 1)];
        integral[y * stride + x] = integral[(y - 1) * stride + x] + row;
      }
    }
    return integral;
  }

  private double outsideExtensionRatio(
      int[] integral,
      int imageWidth,
      int imageHeight,
      int x,
      int y,
      int size,
      int band
  ) {
    int reach = Math.max(6, size / 2);
    double topLeft = integralRatio(integral, imageWidth, x - reach, y, x, y + band);
    double topRight = integralRatio(integral, imageWidth, x + size, y, x + size + reach, y + band);
    double bottomLeft = integralRatio(integral, imageWidth, x - reach, y + size - band, x, y + size);
    double bottomRight = integralRatio(integral, imageWidth, x + size, y + size - band, x + size + reach, y + size);
    double leftTop = integralRatio(integral, imageWidth, x, y - reach, x + band, y);
    double leftBottom = integralRatio(integral, imageWidth, x, y + size, x + band, y + size + reach);
    double rightTop = integralRatio(integral, imageWidth, x + size - band, y - reach, x + size, y);
    double rightBottom = integralRatio(
        integral,
        imageWidth,
        x + size - band,
        y + size,
        x + size,
        Math.min(imageHeight, y + size + reach)
    );
    return Math.max(
        Math.max(Math.max(topLeft, topRight), Math.max(bottomLeft, bottomRight)),
        Math.max(Math.max(leftTop, leftBottom), Math.max(rightTop, rightBottom))
    );
  }

  private boolean hasDiagonalStroke(int[] integral, int imageWidth, int x, int y, int size) {
    int hit = 0;
    int total = 0;
    int pad = Math.max(3, size / 5);
    for (int offset = pad; offset < size - pad; offset += 1) {
      int sampleX = x + offset;
      int sampleY = y + size - offset;
      double density = integralRatio(integral, imageWidth, sampleX - 1, sampleY - 1, sampleX + 2, sampleY + 2);
      if (density > 0.18) {
        hit += 1;
      }
      total += 1;
    }
    return total > 0 && ((double) hit / total) > 0.28;
  }

  private boolean hasHorizontalRun(byte[] dark, int imageWidth, int x, int y, int size, int band) {
    int targetRun = Math.max(7, Math.round(size * 0.42f));
    for (int row = y; row < y + band; row += 1) {
      int run = 0;
      for (int col = x; col < x + size; col += 1) {
        if (dark[row * imageWidth + col] != 0) {
          run += 1;
          if (run >= targetRun) {
            return true;
          }
        } else {
          run = 0;
        }
      }
    }
    return false;
  }

  private boolean hasVerticalRun(byte[] dark, int imageWidth, int x, int y, int size, int band) {
    int targetRun = Math.max(7, Math.round(size * 0.42f));
    for (int col = x; col < x + band; col += 1) {
      int run = 0;
      for (int row = y; row < y + size; row += 1) {
        if (dark[row * imageWidth + col] != 0) {
          run += 1;
          if (run >= targetRun) {
            return true;
          }
        } else {
          run = 0;
        }
      }
    }
    return false;
  }

  private double integralRatio(int[] integral, int imageWidth, int x0, int y0, int x1, int y1) {
    int stride = imageWidth + 1;
    int imageHeight = integral.length / stride - 1;
    int left = clamp(x0, 0, imageWidth);
    int top = clamp(y0, 0, imageHeight);
    int right = clamp(x1, left, imageWidth);
    int bottom = clamp(y1, top, imageHeight);
    int area = Math.max(1, (right - left) * (bottom - top));
    int count = integral[bottom * stride + right]
        - integral[top * stride + right]
        - integral[bottom * stride + left]
        + integral[top * stride + left];
    return (double) count / area;
  }

  private double innerDarkRatio(Component component, int imageWidth, byte[] dark) {
    int pad = Math.max(2, Math.round(Math.min(component.width(), component.height()) * 0.22f));
    int x0 = clamp(component.x0() + pad, component.x0(), component.x1());
    int y0 = clamp(component.y0() + pad, component.y0(), component.y1());
    int x1 = clamp(component.x1() - pad, x0, component.x1());
    int y1 = clamp(component.y1() - pad, y0, component.y1());
    return ratioInRegion(x0, y0, x1, y1, imageWidth, dark);
  }

  private EdgeSupport edgeSupport(Component component, int imageWidth, byte[] dark) {
    int band = Math.max(1, Math.round(Math.min(component.width(), component.height()) * 0.14f));
    double top = ratioInRegion(component.x0(), component.y0(), component.x1(), component.y0() + band, imageWidth, dark);
    double bottom = ratioInRegion(component.x0(), component.y1() - band, component.x1(), component.y1(), imageWidth, dark);
    double left = ratioInRegion(component.x0(), component.y0(), component.x0() + band, component.y1(), imageWidth, dark);
    double right = ratioInRegion(component.x1() - band, component.y0(), component.x1(), component.y1(), imageWidth, dark);
    return new EdgeSupport(top, bottom, left, right);
  }

  private double ratioInRegion(int x0, int y0, int x1, int y1, int imageWidth, byte[] dark) {
    int total = 0;
    int count = 0;
    for (int y = y0; y < y1; y += 1) {
      for (int x = x0; x < x1; x += 1) {
        total += 1;
        if (dark[y * imageWidth + x] != 0) {
          count += 1;
        }
      }
    }
    return total == 0 ? 0 : (double) count / total;
  }

  private List<Candidate> mergeCloseCandidates(List<Candidate> candidates) {
    return candidates.stream()
        .sorted(Comparator
            .comparingDouble(Candidate::confidence)
            .reversed()
            .thenComparingInt(candidate -> candidate.bbox().get(1))
            .thenComparingInt(candidate -> candidate.bbox().get(0)))
        .filter(new CandidateDeduplicator()::keep)
        .sorted(Comparator
            .comparingInt((Candidate candidate) -> candidate.bbox().get(1))
            .thenComparingInt(candidate -> candidate.bbox().get(0)))
        .toList();
  }

  private String bindLabel(Candidate candidate, List<OcrTextBlock> blocks) {
    if (blocks == null || blocks.isEmpty()) {
      return "";
    }
    int x1 = candidate.bbox().get(2);
    int y0 = candidate.bbox().get(1);
    int y1 = candidate.bbox().get(3);
    double centerY = (y0 + y1) / 2.0;
    OcrTextBlock best = null;
    double bestScore = Double.MAX_VALUE;
    for (OcrTextBlock block : blocks) {
      List<Integer> bbox = block.bbox();
      if (bbox == null || bbox.size() < 4 || block.content() == null || block.content().isBlank()) {
        continue;
      }
      int blockX0 = bbox.get(0);
      int blockY0 = bbox.get(1);
      int blockY1 = bbox.get(3);
      double blockCenterY = (blockY0 + blockY1) / 2.0;
      double verticalGap = Math.abs(centerY - blockCenterY);
      double allowedVerticalGap = Math.max((y1 - y0) * 1.6, (blockY1 - blockY0) * 0.65);
      if (blockX0 + 4 < x1 || verticalGap > allowedVerticalGap) {
        continue;
      }
      double score = (blockX0 - x1) + verticalGap * 2.0;
      if (score < bestScore) {
        best = block;
        bestScore = score;
      }
    }
    return best == null ? "" : compactLabel(best.content());
  }

  private boolean hasFillableTableBlocks(List<OcrTextBlock> blocks, int imageWidth, int imageHeight) {
    if (blocks == null) {
      return false;
    }
    return blocks.stream().anyMatch(block -> isFillableTableBlock(block, imageWidth, imageHeight));
  }

  private boolean isInsideFillableTable(Candidate candidate, List<OcrTextBlock> blocks, int imageWidth, int imageHeight) {
    if (blocks == null) {
      return false;
    }
    double centerX = (candidate.bbox().get(0) + candidate.bbox().get(2)) / 2.0;
    double centerY = (candidate.bbox().get(1) + candidate.bbox().get(3)) / 2.0;
    for (OcrTextBlock block : blocks) {
      if (!isFillableTableBlock(block, imageWidth, imageHeight)) {
        continue;
      }
      List<Integer> bbox = block.bbox();
      if (centerX >= bbox.get(0) && centerX <= bbox.get(2) && centerY >= bbox.get(1) && centerY <= bbox.get(3)) {
        return true;
      }
    }
    return false;
  }

  private boolean isFillableTableBlock(OcrTextBlock block, int imageWidth, int imageHeight) {
    List<Integer> bbox = block.bbox();
    if (!"table".equalsIgnoreCase(block.label()) || bbox == null || bbox.size() < 4) {
      return false;
    }
    int width = bbox.get(2) - bbox.get(0);
    int height = bbox.get(3) - bbox.get(1);
    return width >= imageWidth * 0.45 && height >= imageHeight * 0.08;
  }

  private String compactLabel(String value) {
    String text = HTML_TAG.matcher(value).replaceAll(" ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replaceAll("\\s+", " ")
        .trim();
    if (text.length() <= 96) {
      return text;
    }
    return text.substring(0, 96).trim() + "...";
  }

  private BufferedImage decodeImage(String imageDataUrl) {
    if (imageDataUrl == null || imageDataUrl.isBlank() || imageDataUrl.startsWith("http")) {
      return null;
    }
    String base64 = imageDataUrl;
    int comma = base64.indexOf(',');
    if (comma >= 0) {
      base64 = base64.substring(comma + 1);
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(base64);
      return ImageIO.read(new ByteArrayInputStream(bytes));
    } catch (IllegalArgumentException | IOException exception) {
      return null;
    }
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private double clampDouble(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  public record DetectionResult(int imageWidth, int imageHeight, List<OcrCheckbox> checkboxes) {}

  private record Component(int x0, int y0, int x1, int y1, int pixels) {
    int width() {
      return x1 - x0;
    }

    int height() {
      return y1 - y0;
    }
  }

  private record EdgeSupport(double top, double bottom, double left, double right) {}

  private record Candidate(List<Integer> bbox, boolean checked, double confidence) {}

  private static final class CandidateDeduplicator {
    private final List<Candidate> kept = new ArrayList<>();

    boolean keep(Candidate candidate) {
      for (Candidate previous : kept) {
        if (intersectionOverUnion(previous.bbox(), candidate.bbox()) > 0.25) {
          return false;
        }
      }
      kept.add(candidate);
      return true;
    }

    private double intersectionOverUnion(List<Integer> left, List<Integer> right) {
      int x0 = Math.max(left.get(0), right.get(0));
      int y0 = Math.max(left.get(1), right.get(1));
      int x1 = Math.min(left.get(2), right.get(2));
      int y1 = Math.min(left.get(3), right.get(3));
      int intersection = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
      int leftArea = Math.max(1, (left.get(2) - left.get(0)) * (left.get(3) - left.get(1)));
      int rightArea = Math.max(1, (right.get(2) - right.get(0)) * (right.get(3) - right.get(1)));
      return (double) intersection / (leftArea + rightArea - intersection);
    }
  }
}
