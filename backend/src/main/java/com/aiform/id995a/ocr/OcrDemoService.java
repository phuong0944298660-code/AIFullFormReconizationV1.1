package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.StructuredExtractionGateway;
import com.aiform.id995a.llm.StructuredExtractionResult;
import com.aiform.id995a.llm.ExtractionProgressListener;
import com.aiform.id995a.review.EngineStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OcrDemoService {

  private final BaiduOcrPageRenderer pageRenderer;
  private final StructuredExtractionGateway structuredExtractionGateway;
  private final StructuredFieldEvidenceService structuredFieldEvidenceService;

  public OcrDemoService(
      BaiduOcrPageRenderer pageRenderer,
      StructuredExtractionGateway structuredExtractionGateway,
      StructuredFieldEvidenceService structuredFieldEvidenceService
  ) {
    this.pageRenderer = pageRenderer;
    this.structuredExtractionGateway = structuredExtractionGateway;
    this.structuredFieldEvidenceService = structuredFieldEvidenceService;
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes) throws IOException {
    List<RenderedOcrPage> pages = pageRenderer.render(filename, contentType, fileBytes);
    return recognizeRendered(normalizeFilename(filename), pages, ExtractionProgressListener.NOOP);
  }

  public OcrDemoResponse recognizeRendered(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener
  ) throws IOException {
    String normalizedFilename = normalizeFilename(filename);
    StructuredExtractionResult extraction = structuredExtractionGateway.extract(normalizedFilename, pages, progressListener);
    Map<Integer, List<StructuredFieldDetail>> fieldDetailsByPage =
        structuredFieldEvidenceService.buildFieldDetails(extraction.data(), pages);
    List<OcrPage> responsePages = pages.stream()
        .map(page -> new OcrPage(
            page.page(),
            page.sourceImageDataUrl(),
            page.imageWidth(),
            page.imageHeight(),
            "",
            List.of(),
            List.of(),
            List.of(),
            fieldDetailsByPage.getOrDefault(page.page(), List.of())
        ))
        .toList();
    EngineStatus status = new EngineStatus(
        extraction.model() + " multimodal structured extraction",
        false,
        List.of(
            "Rendered " + responsePages.size() + " page snapshot(s) and extracted structured JSON with " + extraction.model() + ".",
            "Rendered page snapshots were sent directly to the multimodal LLM to find fields and filled regions; no preset field list or manual template coordinate boxes were used.",
            "Field names, filled values, checkbox selections, signatures, confidence, and optional field-region snapshots come from the multimodal LLM result only."
        )
    );
    return new OcrDemoResponse(
        normalizedFilename,
        extraction.model(),
        responsePages.size(),
        responsePages,
        List.of(),
        status,
        extraction.data(),
        extraction.rawText()
    );
  }

  String normalizeFilename(String filename) {
    return filename == null || filename.isBlank() ? "uploaded-document" : filename;
  }
}
