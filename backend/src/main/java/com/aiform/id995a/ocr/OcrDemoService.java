package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.ExtractionProgressListener;
import com.aiform.id995a.llm.LlmModelProfile;
import com.aiform.id995a.llm.LlmModelRegistry;
import com.aiform.id995a.llm.StructuredExtractionGateway;
import com.aiform.id995a.llm.StructuredExtractionResult;
import com.aiform.id995a.review.EngineStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private final TemplateDetectionService templateDetectionService;
  private final TemplateClassificationLogService templateClassificationLogService;
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
      TemplateDetectionService templateDetectionService,
      TemplateClassificationLogService templateClassificationLogService,
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
    this.templateDetectionService = templateDetectionService;
    this.templateClassificationLogService = templateClassificationLogService;
    this.llmModelRegistry = llmModelRegistry;
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes) throws IOException {
    return recognize(filename, contentType, fileBytes, null);
  }

  public OcrDemoResponse recognize(String filename, String contentType, byte[] fileBytes, String modelId) throws IOException {
    List<RenderedOcrPage> pages = pageRenderer.render(filename, contentType, fileBytes);
    DocumentTemplate template = templateDetectionService.detect(filename, contentType, fileBytes, pages);
    return recognizeRendered(normalizeFilename(filename), pages, ExtractionProgressListener.NOOP, modelId, template);
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
    DocumentTemplate template = templateDetectionService.detect(filename, "", null, pages);
    return recognizeRendered(filename, pages, progressListener, modelId, template);
  }

  public OcrDemoResponse recognizeRendered(
      String filename,
      List<RenderedOcrPage> pages,
      ExtractionProgressListener progressListener,
      String modelId,
      DocumentTemplate template
  ) throws IOException {
    String normalizedFilename = normalizeFilename(filename);
    List<RenderedOcrPage> safePages = pages == null ? List.of() : pages;
    DocumentTemplate resolvedTemplate = template == null
        ? templateDetectionService.detect(normalizedFilename, "", null, safePages)
        : template;
    LlmModelProfile modelProfile = llmModelRegistry.resolve(modelId);
    ExtractionProgressListener listener = progressListener == null ? ExtractionProgressListener.NOOP : progressListener;
    StructuredExtractionResult extraction = structuredExtractionGateway.extract(normalizedFilename, safePages, listener, modelProfile);
    listener.postProcessingStep("address_crop_review", "Reviewing address fields.", 72);
    AddressFieldCropRefinementResult refinedExtraction = addressFieldCropRefinementService.refine(
        normalizedFilename,
        extraction.data(),
        safePages,
        modelProfile
    );
    listener.postProcessingStep("field_crop_review", "Reviewing ordinary and symbol-sensitive fields.", 80);
    GeneralFieldCropRefinementResult refinedGeneralFields = generalFieldCropRefinementService.refine(
        normalizedFilename,
        refinedExtraction.data(),
        safePages,
        modelProfile
    );
    listener.postProcessingStep("selection_crop_review", "Reviewing checkbox and declaration fields.", 88);
    SelectionFieldCropRefinementResult refinedSelections = selectionFieldCropRefinementService.refine(
        normalizedFilename,
        refinedGeneralFields.data(),
        safePages,
        modelProfile,
        resolvedTemplate
    );
    listener.postProcessingStep("declaration_footer_review", "Restoring declaration footer fields.", 92);
    DeclarationFooterFieldRefinementResult refinedFooterFields = declarationFooterFieldRefinementService.refine(
        normalizedFilename,
        refinedSelections.data(),
        safePages,
        modelProfile
    );
    listener.postProcessingStep("smudge_filter", "Filtering smudges, erasures, and correction marks.", 96);
    SmudgedFieldValueFilterResult filteredExtraction = smudgedFieldValueFilterService.filter(refinedFooterFields.data());
    listener.postProcessingStep("field_evidence", "Building field snapshots and display results.", 98);
    JsonNode finalStructuredData = withTemplateMetadata(filteredExtraction.data(), resolvedTemplate);
    templateClassificationLogService.record(normalizedFilename, resolvedTemplate);
    Map<Integer, List<StructuredFieldDetail>> fieldDetailsByPage =
        structuredFieldEvidenceService.buildFieldDetails(finalStructuredData, safePages);
    List<OcrPage> responsePages = safePages.stream()
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
            "Detected document template: " + resolvedTemplate.templateId() + " (" + resolvedTemplate.matchSource() + ").",
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
        finalStructuredData,
        extraction.rawText()
    );
  }

  private JsonNode withTemplateMetadata(JsonNode data, DocumentTemplate template) {
    ObjectNode root = data != null && data.isObject()
        ? data.deepCopy()
        : JsonNodeFactory.instance.objectNode();
    ObjectNode metadata = root.putObject("_template");
    metadata.put("template_id", template.templateId());
    metadata.put("footer_id", template.footerId());
    metadata.put("page_count", template.pageCount());
    metadata.put("confidence", template.confidence());
    metadata.put("match_source", template.matchSource());
    metadata.put("structure_hash", template.structureHash());
    return root;
  }

  String normalizeFilename(String filename) {
    return filename == null || filename.isBlank() ? "uploaded-document" : filename;
  }
}
