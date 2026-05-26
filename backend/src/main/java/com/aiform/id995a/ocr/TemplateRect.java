package com.aiform.id995a.ocr;

import java.util.Arrays;

public record TemplateRect(double x, double y, double width, double height) {

  public static TemplateRect parse(String value) {
    String normalized = value == null ? "" : value.replace("[", "").replace("]", "").trim();
    double[] parts = Arrays.stream(normalized.split(","))
        .filter(part -> !part.isBlank())
        .mapToDouble(part -> Double.parseDouble(part.trim()))
        .toArray();
    if (parts.length != 4) {
      throw new IllegalArgumentException("Template rectangle must have 4 values: " + value);
    }
    return new TemplateRect(parts[0], parts[1], parts[2], parts[3]);
  }
}
