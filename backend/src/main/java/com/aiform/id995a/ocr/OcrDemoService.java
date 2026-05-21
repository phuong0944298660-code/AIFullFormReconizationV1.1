package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.StructuredExtractionGateway;
import com.aiform.id995a.llm.StructuredExtractionResult;
import com.aiform.id995a.llm.ExtractionProgressListener;
import com.aiform.id995a.llm.LlmModelProfile;
import com.aiform.id995a.llm.LlmModelRegistry;
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
  private final LlmModelRegistry llmModelRegistry;

  public OcrDemoService(
      BaiduOcrPageRenderer pageRenderer,
      StructuredExtractionGateway structuredExtractionGateway,
      StructuredFieldEvidenceService structuredFieldEvidenceService,
      LlmModelRegistry llmModelRegistry
  ) {
    this.pageRenderer = pageRenderer;
    this.structuredExtractionGateway = structuredExtractionGateway;
    this.structuredFieldEvidenceService = structuredFieldEvidenceService;
    this.llmModelRegistry = llmModelRegistry;
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes) throws IOException {
    return recognize(filename, contentType, fileBytes, null);
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes, String modelId) throws IOException {
    List<RenderedOcrPage> pages = pageRenderer.render(filename, contentType, fileBytes);
    return recognizeRendered(normalizeFilename(filename), pages, ExtractionProgressListener.NOOP, modelId);
  }

  public OcrDemoResponse recognizeRendered(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener
  ) throws IOException {
    return recognizeRendered(filename, pages, progressListener, null);
  }

  public OcrDemoResponse recognizeRendered(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener,
      String modelId
  ) throws IOException {
    String normalizedFilename = normalizeFilename(filename);
    LlmModelProfile modelProfile = llmModelRegistry.resolve(modelId);
    StructuredExtractionResult extraction = structuredExtractionGateway.extract(normalizedFilename, pages, progressListener, modelProfile);
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
        modelProfile.label(),
        false,
        List.of(
            "Rendered " + responsePages.size() + " page snapshot(s) and extracted structured JSON with " + modelProfile.label() + ".",
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
