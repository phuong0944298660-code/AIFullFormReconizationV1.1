package com.aiform.id995a.ocr;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FieldTextNormalizer {

  private static final Pattern NUMBERS = Pattern.compile("\\d+");

  public NormalizedText normalize(String fieldPath, String fieldLabel, String value) {
    FieldKind kind = fieldKind(fieldPath, fieldLabel);
    String text = nfkc(value);
    List<NormalizedChar> chars = switch (kind) {
      case PHONE -> normalizeByRule(text, FieldTextNormalizer::phoneChar);
      case DATE -> normalizeDate(text);
      case ENGLISH_NAME -> normalizeName(text);
      case ADDRESS -> normalizeAddress(text);
      case ID_DOCUMENT -> normalizeByRule(text, FieldTextNormalizer::idDocumentChar);
      case GENERIC -> normalizeGeneric(text);
    };
    return new NormalizedText(kind, chars);
  }

  public boolean formatValid(String fieldPath, String fieldLabel, String value) {
    FieldKind kind = fieldKind(fieldPath, fieldLabel);
    String text = nfkc(value).trim();
    if (text.isBlank()) {
      return true;
    }
    return switch (kind) {
      case PHONE -> normalize(fieldPath, fieldLabel, value).value().matches("\\+?\\d{5,18}");
      case DATE -> normalize(fieldPath, fieldLabel, value).value().matches("\\d{4}-\\d{2}-\\d{2}|\\d{4}-\\d{2}");
      case ENGLISH_NAME -> text.matches("[A-Za-z][A-Za-z .'\\-]*(?: [A-Za-z][A-Za-z .'\\-]*)*");
      case ID_DOCUMENT -> text.matches("[A-Za-z0-9 ]+");
      case ADDRESS, GENERIC -> true;
    };
  }

  private FieldKind fieldKind(String fieldPath, String fieldLabel) {
    String text = (fieldPath + " " + fieldLabel).toLowerCase(Locale.ROOT);
    if (text.matches(".*(phone|telephone|tel|contact).*")) {
      return FieldKind.PHONE;
    }
    if (text.matches(".*(date|birth|issue|expiry|period|from|to).*")) {
      return FieldKind.DATE;
    }
    if (text.matches(".*(passport|identity|document.*no|document_no|travel.*no|id card|hk.*id|证件|護照|护照|身份).*")) {
      return FieldKind.ID_DOCUMENT;
    }
    if (text.matches(".*(surname|given.*name|name.*english|english.*name|姓名|英文名).*")) {
      return FieldKind.ENGLISH_NAME;
    }
    if (text.matches(".*(address|domicile|地址).*")) {
      return FieldKind.ADDRESS;
    }
    return FieldKind.GENERIC;
  }

  private List<NormalizedChar> normalizeDate(String text) {
    List<String> numbers = new ArrayList<>();
    Matcher matcher = NUMBERS.matcher(text);
    while (matcher.find()) {
      numbers.add(matcher.group());
    }
    if (numbers.size() >= 3) {
      int first = parseInt(numbers.get(0));
      int second = parseInt(numbers.get(1));
      int third = parseInt(numbers.get(2));
      int year;
      int month;
      int day;
      if (numbers.get(0).length() == 4) {
        year = first;
        month = second;
        day = third;
      } else {
        day = first;
        month = second;
        year = normalizeYear(third);
      }
      if (validDate(year, month, day)) {
        return mappedLiteral(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day));
      }
    }
    if (numbers.size() == 2) {
      int month = parseInt(numbers.get(0));
      int year = normalizeYear(parseInt(numbers.get(1)));
      if (month >= 1 && month <= 12) {
        return mappedLiteral(String.format(Locale.ROOT, "%04d-%02d", year, month));
      }
    }
    return normalizeGeneric(text);
  }

  private List<NormalizedChar> normalizeName(String text) {
    return collapseSpaces(text.toUpperCase(Locale.ROOT), true);
  }

  private List<NormalizedChar> normalizeAddress(String text) {
    String unified = text
        .replace('，', ',')
        .replace('。', '.')
        .replace('、', ',')
        .replace('；', ';')
        .replace('：', ':')
        .replace('（', '(')
        .replace('）', ')');
    return collapseSpaces(unified.toUpperCase(Locale.ROOT), true);
  }

  private List<NormalizedChar> normalizeGeneric(String text) {
    return collapseSpaces(text.toUpperCase(Locale.ROOT), true);
  }

  private List<NormalizedChar> collapseSpaces(String text, boolean keepSpace) {
    List<NormalizedChar> chars = new ArrayList<>();
    boolean previousSpace = false;
    for (int offset = 0; offset < text.length();) {
      int codePoint = text.codePointAt(offset);
      String value = new String(Character.toChars(codePoint));
      boolean space = Character.isWhitespace(codePoint);
      if (space) {
        if (keepSpace && !previousSpace && !chars.isEmpty()) {
          chars.add(new NormalizedChar(" ", offset));
        }
        previousSpace = true;
      } else {
        chars.add(new NormalizedChar(value, offset));
        previousSpace = false;
      }
      offset += Character.charCount(codePoint);
    }
    if (!chars.isEmpty() && " ".equals(chars.get(chars.size() - 1).value())) {
      chars.remove(chars.size() - 1);
    }
    return List.copyOf(chars);
  }

  private static String phoneChar(String value) {
    if (value.matches("[\\s\\-‐‑‒–—―()]")) {
      return "";
    }
    if (value.equals("+") || value.matches("\\d")) {
      return value;
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private static String idDocumentChar(String value) {
    String upper = value.toUpperCase(Locale.ROOT);
    return upper.matches("[A-Z0-9]") ? upper : "";
  }

  private List<NormalizedChar> normalizeByRule(String text, CharRule rule) {
    List<NormalizedChar> chars = new ArrayList<>();
    for (int offset = 0; offset < text.length();) {
      int codePoint = text.codePointAt(offset);
      String normalized = rule.apply(new String(Character.toChars(codePoint)));
      if (!normalized.isBlank()) {
        chars.add(new NormalizedChar(normalized, offset));
      }
      offset += Character.charCount(codePoint);
    }
    return List.copyOf(chars);
  }

  private List<NormalizedChar> mappedLiteral(String value) {
    List<NormalizedChar> chars = new ArrayList<>();
    for (int index = 0; index < value.length(); index += 1) {
      chars.add(new NormalizedChar(String.valueOf(value.charAt(index)), index));
    }
    return List.copyOf(chars);
  }

  private String nfkc(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
  }

  private int normalizeYear(int year) {
    if (year >= 100) {
      return year;
    }
    return year >= 80 ? 1900 + year : 2000 + year;
  }

  private int parseInt(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      return -1;
    }
  }

  private boolean validDate(int year, int month, int day) {
    return year >= 1900 && year <= 2099 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
  }

  public enum FieldKind {
    PHONE,
    DATE,
    ENGLISH_NAME,
    ADDRESS,
    ID_DOCUMENT,
    GENERIC
  }

  public record NormalizedText(FieldKind kind, List<NormalizedChar> chars) {
    public NormalizedText {
      chars = chars == null ? List.of() : List.copyOf(chars);
    }

    public String value() {
      StringBuilder builder = new StringBuilder();
      for (NormalizedChar character : chars) {
        builder.append(character.value());
      }
      return builder.toString();
    }
  }

  public record NormalizedChar(String value, int sourceOffset) {}

  private interface CharRule {
    String apply(String value);
  }
}
