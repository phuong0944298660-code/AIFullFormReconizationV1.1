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
  private final AddressFieldCropRefinementService addressFieldCropRefinementService;
  private final GeneralFieldCropRefinementService generalFieldCropRefinementService;
  private final SelectionFieldCropRefinementService selectionFieldCropRefinementService;
  private final DeclarationFooterFieldRefinementService declarationFooterFieldRefinementService;
  private final SmudgedFieldValueFilterService smudgedFieldValueFilterService;
  private final LlmModelRegistry llmModelRegistry;

  public OcrDemoService(
      BaiduOcrPageRenderer pageRenderer,
      StructuredExtractionGateway structuredExtractionGateway,
      StructuredFieldEvidenceService structuredFieldEvidenceService,
      AddressFieldCropRefinementService addressFieldCropRefinementService,
      GeneralFieldCropRefinementService generalFieldCropRefinementService,
      SelectionFieldCropRefinementService selectionFieldCropRefinementService,
      DeclarationFooterFieldRefinementService declarationFooterFieldRefinementService,
      SmudgedFieldValueFilterService smudgedFieldValueFilterService,
      LlmModelRegistry llmModelRegistry
  ) {
    this.pageRenderer = pageRenderer;
    this.structuredExtractionGateway = structuredExtractionGateway;
    this.structuredFieldEvidenceService = structuredFieldEvidenceService;
    this.addressFieldCropRefinementService = addressFieldCropRefinementService;
    this.generalFieldCropRefinementService = generalFieldCropRefinementService;
    this.selectionFieldCropRefinementService = selectionFieldCropRefinementService;
    this.declarationFooterFieldRefinementService = declarationFooterFieldRefinementService;
    this.smudgedFieldValueFilterService = smudgedFieldValueFilterService;
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
    ExtractionProgressListener listener = progressListener == null ? ExtractionProgressListener.NOOP : progressListener;
    StructuredExtractionResult extraction = structuredExtractionGateway.extract(normalizedFilename, pages, listener, modelProfile);
    listener.postProcessingStep("address_crop_review", "整页识别完成，正在复核地址字段。", 72);
    AddressFieldCropRefinementResult refinedExtraction = addressFieldCropRefinementService.refine(
        normalizedFilename,
        extraction.data(),
        pages,
        modelProfile
    );
    listener.postProcessingStep("field_crop_review", "正在复核普通字段和符号敏感字段。", 80);
    GeneralFieldCropRefinementResult refinedGeneralFields = generalFieldCropRefinementService.refine(
        normalizedFilename,
        refinedExtraction.data(),
        pages,
        modelProfile
    );
    listener.postProcessingStep("selection_crop_review", "正在复核勾选项和声明字段。", 88);
    SelectionFieldCropRefinementResult refinedSelections = selectionFieldCropRefinementService.refine(
        normalizedFilename,
        refinedGeneralFields.data(),
        pages,
        modelProfile
    );
    listener.postProcessingStep("declaration_footer_review", "正在补充声明页日期和签名字段。", 92);
    DeclarationFooterFieldRefinementResult refinedFooterFields = declarationFooterFieldRefinementService.refine(
        normalizedFilename,
        refinedSelections.data(),
        pages,
        modelProfile
    );
    listener.postProcessingStep("smudge_filter", "正在过滤涂抹、擦除和修正痕迹。", 96);
    SmudgedFieldValueFilterResult filteredExtraction = smudgedFieldValueFilterService.filter(refinedFooterFields.data());
    listener.postProcessingStep("field_evidence", "正在生成字段快照和展示结果。", 98);
    Map<Integer, List<StructuredFieldDetail>> fieldDetailsByPage =
        structuredFieldEvidenceService.buildFieldDetails(filteredExtraction.data(), pages);
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
            "Address fields with clear value regions are second-pass transcribed from their field crop; updated fields: " + refinedExtraction.updated() + " / " + refinedExtraction.attempted() + ".",
            "Ordinary text and symbol-sensitive fields with clear value regions are crop-reviewed by field type; updated fields: " + refinedGeneralFields.updated() + " / " + refinedGeneralFields.attempted() + ".",
            "Checkbox and declaration fields with clear value regions are second-pass reviewed from their field crop; updated fields: " + refinedSelections.updated() + " / " + refinedSelections.attempted() + ".",
            "Declaration page checkbox/date/signature fields are restored from fixed page crops when the page model misses them; updated fields: " + refinedFooterFields.updated() + " / " + refinedFooterFields.attempted() + ".",
            "Smudged, crossed-out, erased, or correction marks mixed into field values are filtered as not filled; filtered fields: " + filteredExtraction.filtered() + "."
        )
    );
    return new OcrDemoResponse(
        normalizedFilename,
        extraction.model(),
        responsePages.size(),
        responsePages,
        List.of(),
        status,
        filteredExtraction.data(),
        extraction.rawText()
    );
  }

  String normalizeFilename(String filename) {
    return filename == null || filename.isBlank() ? "uploaded-document" : filename;
  }
}
