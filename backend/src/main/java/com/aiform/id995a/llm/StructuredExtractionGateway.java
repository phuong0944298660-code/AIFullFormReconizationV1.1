package com.aiform.id995a.llm;

import com.aiform.id995a.ocr.RenderedOcrPage;
import java.io.IOException;
import java.util.List;

public interface StructuredExtractionGateway {
  default StructuredExtractionResult extract(String filename, List<RenderedOcrPage> pages) throws IOException {
    return extract(filename, pages, ExtractionProgressListener.NOOP);
  }

  StructuredExtractionResult extract(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener
  ) throws IOException;

  default StructuredExtractionResult extract(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener,
      LlmModelProfile modelProfile
  ) throws IOException {
    return extract(filename, pages, progressListener);
  }
}
