package com.aiform.id995a.ocr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OcrTextHighlighter {

  private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
  private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
  private static final Pattern FIELD_KEYWORD = Pattern.compile(
      "(?iu)(surname|given names?|name|date|birth|nationality|address|passport|travel document|"
          + "phone|email|school|institution|employer|occupation|salary|wage|income|amount|period|"
          + "place|country|city|signature|姓名|出生|日期|地址|國籍|国籍|護照|护照|電話|电话|學校|学校|"
          + "雇主|職業|职业|工資|工资|金額|金额|簽署|签署)"
  );
  private static final Pattern DATE_VALUE = Pattern.compile(
      "\\b\\d{1,4}[./\\-年]\\d{1,2}(?:[./\\-月]\\d{1,4})?日?\\b"
  );
  private static final Pattern EMAIL_VALUE = Pattern.compile(
      "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
      Pattern.CASE_INSENSITIVE
  );
  private static final Pattern ID_VALUE = Pattern.compile("\\b[A-Z]{1,3}\\d[A-Z0-9]{3,}\\b");
  private static final Pattern MONEY_VALUE = Pattern.compile(
      "(?:HKD|HK\\$|RMB|CNY|USD|\\$)\\s?\\d[\\d,]*(?:\\.\\d+)?\\b",
      Pattern.CASE_INSENSITIVE
  );
  private static final Pattern UPPERCASE_VALUE = Pattern.compile(
      "\\b[A-Z][A-Z.'\\-]{1,}(?:\\s+[A-Z][A-Z.'\\-]{1,}){0,5}\\b"
  );
  private static final Pattern EXPLICIT_UNDERLINE_VALUE = Pattern.compile("_{2,}\\s*([^_\\n]{2,}?)\\s*_{2,}");
  private static final Set<String> STOP_VALUES = Set.of(
      "BLOCK", "ENGLISH", "CHINESE", "APPLICATION", "TYPE", "NOTE", "GUIDEBOOK",
      "ENTRY", "STUDY", "HONG", "KONG", "PLEASE", "DATE", "BIRTH", "NAME"
  );

  private OcrTextHighlighter() {}

  public static List<OcrTextLine> toLines(String markdown) {
    String plainText = markdownToPlainText(markdown);
    List<String> rawLines = suppressStandaloneNumberRuns(plainText.split("\\R"));
    List<OcrTextLine> lines = new ArrayList<>();
    int visibleLine = 1;
    for (String rawLine : rawLines) {
      String line = normalizeSpaces(rawLine);
      if (line.isBlank()) {
        continue;
      }
      List<OcrTextSpan> spans = toSpans(line);
      boolean hasUserInput = spans.stream().anyMatch(OcrTextSpan::userInput);
      lines.add(new OcrTextLine(visibleLine, List.copyOf(spans), hasUserInput));
      visibleLine += 1;
    }
    return List.copyOf(lines);
  }

  private static List<String> suppressStandaloneNumberRuns(String[] rawLines) {
    List<String> normalizedLines = new ArrayList<>();
    for (String rawLine : rawLines) {
      String line = normalizeSpaces(rawLine);
      if (!line.isBlank()) {
        normalizedLines.add(line);
      }
    }
    List<String> filtered = new ArrayList<>();
    for (int index = 0; index < normalizedLines.size();) {
      int end = index;
      while (end < normalizedLines.size() && isStandaloneLayoutNumber(normalizedLines.get(end))) {
        end += 1;
      }
      if (end - index >= 3) {
        index = end;
        continue;
      }
      filtered.add(normalizedLines.get(index));
      index += 1;
    }
    return List.copyOf(filtered);
  }

  private static boolean isStandaloneLayoutNumber(String line) {
    return line.matches("\\d{1,2}");
  }

  public static boolean hasLikelyUserInput(String text) {
    return toSpans(normalizeSpaces(text)).stream().anyMatch(OcrTextSpan::userInput);
  }

  private static List<OcrTextSpan> toSpans(String line) {
    if (line.isBlank()) {
      return List.of();
    }

    List<Range> ranges = new ArrayList<>();
    collectExplicitUnderlineRanges(line, ranges);
    collectPatternRanges(line, EMAIL_VALUE, 0, ranges);
    collectPatternRanges(line, MONEY_VALUE, 0, ranges);
    collectPatternRanges(line, ID_VALUE, 0, ranges);
    collectPatternRanges(line, DATE_VALUE, 0, ranges);

    Matcher fieldMatcher = FIELD_KEYWORD.matcher(line);
    if (fieldMatcher.find()) {
      collectUppercaseValueRanges(line, fieldMatcher.end(), ranges);
    }

    List<Range> merged = mergeRanges(line, ranges);
    if (merged.isEmpty()) {
      return List.of(new OcrTextSpan(line, false));
    }

    List<OcrTextSpan> spans = new ArrayList<>();
    int cursor = 0;
    for (Range range : merged) {
      if (range.start() > cursor) {
        spans.add(new OcrTextSpan(line.substring(cursor, range.start()), false));
      }
      spans.add(new OcrTextSpan(line.substring(range.start(), range.end()), true));
      cursor = range.end();
    }
    if (cursor < line.length()) {
      spans.add(new OcrTextSpan(line.substring(cursor), false));
    }
    return List.copyOf(spans);
  }

  private static void collectExplicitUnderlineRanges(String line, List<Range> ranges) {
    Matcher matcher = EXPLICIT_UNDERLINE_VALUE.matcher(line);
    while (matcher.find()) {
      ranges.add(new Range(matcher.start(1), matcher.end(1)));
    }
  }

  private static void collectPatternRanges(String line, Pattern pattern, int minStart, List<Range> ranges) {
    Matcher matcher = pattern.matcher(line);
    while (matcher.find()) {
      if (matcher.start() >= minStart && isAllowedValue(line.substring(matcher.start(), matcher.end()))) {
        ranges.add(new Range(matcher.start(), matcher.end()));
      }
    }
  }

  private static void collectUppercaseValueRanges(String line, int minStart, List<Range> ranges) {
    Matcher matcher = UPPERCASE_VALUE.matcher(line);
    while (matcher.find()) {
      String value = line.substring(matcher.start(), matcher.end()).trim();
      if (matcher.start() >= minStart && isAllowedValue(value)) {
        ranges.add(new Range(matcher.start(), matcher.end()));
      }
    }
  }

  private static boolean isAllowedValue(String value) {
    String normalized = value.trim().replaceAll("\\s+", " ").toUpperCase();
    return normalized.length() >= 2
        && !STOP_VALUES.contains(normalized)
        && !normalized.startsWith("ID(")
        && !normalized.matches("[IVX]+");
  }

  private static List<Range> mergeRanges(String line, List<Range> ranges) {
    List<Range> sorted = ranges.stream()
        .filter(range -> range.start() >= 0 && range.end() <= line.length() && range.start() < range.end())
        .sorted(Comparator.comparingInt(Range::start).thenComparingInt(Range::end))
        .toList();
    if (sorted.isEmpty()) {
      return List.of();
    }

    List<Range> merged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Range range : sorted) {
      String value = line.substring(range.start(), range.end()).trim();
      if (!seen.add(range.start() + ":" + range.end() + ":" + value)) {
        continue;
      }
      if (merged.isEmpty()) {
        merged.add(range);
        continue;
      }
      Range last = merged.get(merged.size() - 1);
      if (range.start() <= last.end()) {
        merged.set(merged.size() - 1, new Range(last.start(), Math.max(last.end(), range.end())));
      } else {
        merged.add(range);
      }
    }
    return List.copyOf(merged);
  }

  private static String markdownToPlainText(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return "";
    }
    String text = markdown
        .replaceAll("(?is)<img[^>]*>", "")
        .replaceAll("(?i)</tr>", "\n")
        .replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</p>", "\n")
        .replaceAll("(?i)</h[1-6]>", "\n")
        .replaceAll("(?i)</td>\\s*<td[^>]*>", "    ")
        .replaceAll("(?i)<td[^>]*>", "")
        .replaceAll("(?i)</td>", "    ");
    text = HTML_TAG.matcher(text).replaceAll("");
    text = text.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'");
    text = decodeNumericEntities(text);
    return text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
  }

  private static String decodeNumericEntities(String text) {
    Matcher matcher = NUMERIC_ENTITY.matcher(text);
    StringBuilder builder = new StringBuilder();
    while (matcher.find()) {
      String raw = matcher.group(1);
      try {
        int codePoint = raw.startsWith("x") || raw.startsWith("X")
            ? Integer.parseInt(raw.substring(1), 16)
            : Integer.parseInt(raw);
        matcher.appendReplacement(builder, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
      } catch (IllegalArgumentException exception) {
        matcher.appendReplacement(builder, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(builder);
    return builder.toString();
  }

  private static String normalizeSpaces(String text) {
    if (text == null) {
      return "";
    }
    return text.replace('\u00a0', ' ')
        .replaceAll("[\\t ]+", " ")
        .trim();
  }

  private record Range(int start, int end) {}
}
