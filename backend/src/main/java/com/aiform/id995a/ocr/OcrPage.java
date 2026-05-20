package com.aiform.id995a.ocr;

import java.util.List;

public record OcrPage(
    int page,
    String sourceImageDataUrl,
    int imageWidth,
    int imageHeight,
    String markdown,
    List<OcrTextLine> lines,
    List<OcrTextBlock> blocks,
    List<OcrCheckbox> checkboxes,
    List<StructuredFieldDetail> structuredFields
) {

  public OcrPage(
      int page,
      String sourceImageDataUrl,
      int imageWidth,
      int imageHeight,
      String markdown,
      List<OcrTextLine> lines,
      List<OcrTextBlock> blocks,
      List<OcrCheckbox> checkboxes
  ) {
    this(page, sourceImageDataUrl, imageWidth, imageHeight, markdown, lines, blocks, checkboxes, List.of());
  }

  public OcrPage {
    lines = lines == null ? List.of() : List.copyOf(lines);
    blocks = blocks == null ? List.of() : List.copyOf(blocks);
    checkboxes = checkboxes == null ? List.of() : List.copyOf(checkboxes);
    structuredFields = structuredFields == null ? List.of() : List.copyOf(structuredFields);
  }
}
