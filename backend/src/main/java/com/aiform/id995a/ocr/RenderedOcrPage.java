package com.aiform.id995a.ocr;

public record RenderedOcrPage(
    int page,
    byte[] pngBytes,
    String sourceImageDataUrl,
    int imageWidth,
    int imageHeight
) {

  public RenderedOcrPage(int page, String sourceImageDataUrl, int imageWidth, int imageHeight) {
    this(page, new byte[0], sourceImageDataUrl, imageWidth, imageHeight);
  }
}
