package com.aiform.id995a.ocr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OcrCheckboxSemanticLines {

  private static final Pattern TABLE_ROW = Pattern.compile("(?is)<tr[^>]*>.*?</tr>");
  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

  private OcrCheckboxSemanticLines() {}

  public static List<OcrTextLine> merge(
      List<OcrTextLine> lines,
      List<OcrCheckbox> checkboxes,
      List<OcrTextBlock> blocks,
      int imageWidth,
      int imageHeight
  ) {
    List<SemanticLine> semanticLines = checkedSemanticLines(checkboxes, blocks, imageWidth, imageHeight);
    if (semanticLines.isEmpty()) {
      return lines;
    }

    List<OcrTextLine> merged = new ArrayList<>();
    Set<String> inserted = new LinkedHashSet<>();
    for (OcrTextLine line : lines) {
      merged.add(line);
      String text = lineText(line);
      for (SemanticLine semanticLine : semanticLines) {
        if (!inserted.contains(semanticLine.text()) && shouldInsertAfter(text, semanticLine)) {
          merged.add(toOcrLine(0, semanticLine.text()));
          inserted.add(semanticLine.text());
        }
      }
    }
    for (SemanticLine semanticLine : semanticLines) {
      if (inserted.add(semanticLine.text())) {
        merged.add(toOcrLine(0, semanticLine.text()));
      }
    }
    return renumber(merged);
  }

  private static List<SemanticLine> checkedSemanticLines(
      List<OcrCheckbox> checkboxes,
      List<OcrTextBlock> blocks,
      int imageWidth,
      int imageHeight
  ) {
    if (checkboxes == null || checkboxes.isEmpty()) {
      return List.of();
    }
    List<SemanticLine> semanticLines = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (OcrCheckbox checkbox : checkboxes) {
      if (!checkbox.checked()) {
        continue;
      }
      Optional<SemanticLine> semanticLine = describe(checkbox, blocks, imageWidth, imageHeight);
      if (semanticLine.isPresent() && seen.add(semanticLine.get().text())) {
        semanticLines.add(semanticLine.get());
      }
    }
    return List.copyOf(semanticLines);
  }

  private static Optional<SemanticLine> describe(
      OcrCheckbox checkbox,
      List<OcrTextBlock> blocks,
      int imageWidth,
      int imageHeight
  ) {
    String source = findSourceRowText(checkbox, blocks, imageWidth, imageHeight);
    String normalized = source.toLowerCase();
    if ((source.contains("來港受僱") || source.contains("来港受雇")
        || normalized.contains("entry to hong kong to take up employment"))
        && (source.contains("入境") || normalized.contains("entry visa") || normalized.contains("country visa"))) {
      return Optional.of(new SemanticLine(
          "【来港受雇为外籍家庭雇工，入境签证，勾选框已勾选】",
          List.of("Entry to Hong Kong to take up employment", "來港受僱", "来港受雇")
      ));
    }
    if (isFirstApplicationTypeEntryVisaCheckbox(checkbox, blocks, imageWidth, imageHeight)) {
      return Optional.of(new SemanticLine(
          "【来港受雇为外籍家庭雇工，入境签证，勾选框已勾选】",
          List.of("Entry to Hong Kong to take up employment")
      ));
    }
    return Optional.empty();
  }

  private static boolean isFirstApplicationTypeEntryVisaCheckbox(
      OcrCheckbox checkbox,
      List<OcrTextBlock> blocks,
      int imageWidth,
      int imageHeight
  ) {
    if (blocks == null || checkbox.bbox() == null || checkbox.bbox().size() < 4) {
      return false;
    }
    double centerX = (checkbox.bbox().get(0) + checkbox.bbox().get(2)) / 2.0;
    double centerY = (checkbox.bbox().get(1) + checkbox.bbox().get(3)) / 2.0;
    for (OcrTextBlock block : blocks) {
      if (!isCandidateTable(block, imageWidth, imageHeight) || !tableLooksLikeApplicationType(block.content())) {
        continue;
      }
      List<Integer> bbox = block.bbox();
      if (centerX < bbox.get(0) || centerX > bbox.get(2) || centerY < bbox.get(1) || centerY > bbox.get(3)) {
        continue;
      }
      double xRatio = (centerX - bbox.get(0)) / Math.max(1, bbox.get(2) - bbox.get(0));
      double yRatio = (centerY - bbox.get(1)) / Math.max(1, bbox.get(3) - bbox.get(1));
      if (xRatio >= 0.62 && yRatio >= 0.04 && yRatio <= 0.16) {
        return true;
      }
    }
    return false;
  }

  private static boolean tableLooksLikeApplicationType(String content) {
    String normalized = cleanText(content).toLowerCase();
    return normalized.contains("application type")
        && (normalized.contains("entry to hong kong to take up employment")
            || normalized.contains("來港受僱")
            || normalized.contains("来港受雇"));
  }

  private static String findSourceRowText(
      OcrCheckbox checkbox,
      List<OcrTextBlock> blocks,
      int imageWidth,
      int imageHeight
  ) {
    if (blocks == null || checkbox.bbox() == null || checkbox.bbox().size() < 4) {
      return "";
    }
    double centerX = (checkbox.bbox().get(0) + checkbox.bbox().get(2)) / 2.0;
    double centerY = (checkbox.bbox().get(1) + checkbox.bbox().get(3)) / 2.0;
    for (OcrTextBlock block : blocks) {
      if (!isCandidateTable(block, imageWidth, imageHeight)) {
        continue;
      }
      List<Integer> bbox = block.bbox();
      if (centerX < bbox.get(0) || centerX > bbox.get(2) || centerY < bbox.get(1) || centerY > bbox.get(3)) {
        continue;
      }
      List<String> rows = tableRows(block.content());
      if (rows.isEmpty()) {
        return cleanText(block.content());
      }
      double ratio = (centerY - bbox.get(1)) / Math.max(1, bbox.get(3) - bbox.get(1));
      int rowIndex = Math.max(0, Math.min(rows.size() - 1, (int) Math.floor(ratio * rows.size())));
      return rows.get(rowIndex);
    }
    return checkbox.label() == null ? "" : checkbox.label();
  }

  private static boolean isCandidateTable(OcrTextBlock block, int imageWidth, int imageHeight) {
    if (!"table".equalsIgnoreCase(block.label()) || block.bbox() == null || block.bbox().size() < 4) {
      return false;
    }
    int width = block.bbox().get(2) - block.bbox().get(0);
    int height = block.bbox().get(3) - block.bbox().get(1);
    return width >= imageWidth * 0.45 && height >= imageHeight * 0.08;
  }

  private static List<String> tableRows(String html) {
    if (html == null || html.isBlank()) {
      return List.of();
    }
    Matcher matcher = TABLE_ROW.matcher(html);
    List<String> rows = new ArrayList<>();
    while (matcher.find()) {
      String row = cleanText(matcher.group());
      if (!row.isBlank()) {
        rows.add(row);
      }
    }
    return List.copyOf(rows);
  }

  private static boolean shouldInsertAfter(String line, SemanticLine semanticLine) {
    String lowerLine = line.toLowerCase();
    for (String anchor : semanticLine.anchors()) {
      if (lowerLine.contains(anchor.toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  private static OcrTextLine toOcrLine(int lineNumber, String text) {
    return new OcrTextLine(lineNumber, List.of(new OcrTextSpan(text, true)), true);
  }

  private static List<OcrTextLine> renumber(List<OcrTextLine> lines) {
    List<OcrTextLine> renumbered = new ArrayList<>();
    for (int index = 0; index < lines.size(); index += 1) {
      OcrTextLine line = lines.get(index);
      renumbered.add(new OcrTextLine(index + 1, line.spans(), line.hasUserInput()));
    }
    return List.copyOf(renumbered);
  }

  private static String lineText(OcrTextLine line) {
    StringBuilder builder = new StringBuilder();
    for (OcrTextSpan span : line.spans()) {
      builder.append(span.text());
    }
    return builder.toString();
  }

  private static String cleanText(String value) {
    if (value == null) {
      return "";
    }
    return HTML_TAG.matcher(value).replaceAll(" ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x27;", "'")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private record SemanticLine(String text, List<String> anchors) {}
}
