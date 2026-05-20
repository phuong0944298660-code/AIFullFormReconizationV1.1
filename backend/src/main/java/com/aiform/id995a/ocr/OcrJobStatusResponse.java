package com.aiform.id995a.ocr;

import java.util.List;

public record OcrJobStatusResponse(
    String jobId,
    String status,
    int pageCount,
    int completedPages,
    int failedPages,
    int progress,
    Integer activePage,
    List<OcrJobPageProgress> pages,
    String message,
    String error,
    OcrDemoResponse result
) {

  public OcrJobStatusResponse {
    pages = pages == null ? List.of() : List.copyOf(pages);
  }
}
