package com.aiform.id995a.controller;

import com.aiform.id995a.llm.LlmModelOptionsResponse;
import com.aiform.id995a.llm.LlmModelRegistry;
import com.aiform.id995a.ocr.OcrDemoResponse;
import com.aiform.id995a.ocr.OcrDemoService;
import com.aiform.id995a.ocr.OcrJobService;
import com.aiform.id995a.ocr.OcrJobStatusResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class OcrController {

  private final OcrDemoService ocrDemoService;
  private final OcrJobService ocrJobService;
  private final LlmModelRegistry llmModelRegistry;

  public OcrController(OcrDemoService ocrDemoService, OcrJobService ocrJobService, LlmModelRegistry llmModelRegistry) {
    this.ocrDemoService = ocrDemoService;
    this.ocrJobService = ocrJobService;
    this.llmModelRegistry = llmModelRegistry;
  }

  @GetMapping("/llm/models")
  public LlmModelOptionsResponse llmModels() {
    return llmModelRegistry.options();
  }

  @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OcrDemoResponse recognize(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "modelId", required = false) String modelId
  ) throws IOException {
    return ocrDemoService.recognize(file.getOriginalFilename(), file.getContentType(), file.getBytes(), modelId);
  }

  @PostMapping(value = "/ocr/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OcrJobStatusResponse startRecognizeJob(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "modelId", required = false) String modelId
  ) throws IOException {
    return ocrJobService.start(file.getOriginalFilename(), file.getContentType(), file.getBytes(), modelId);
  }

  @GetMapping("/ocr/jobs/{jobId}")
  public OcrJobStatusResponse recognizeJobStatus(@PathVariable String jobId) {
    return ocrJobService.status(jobId);
  }

  @DeleteMapping("/ocr/jobs/{jobId}")
  public OcrJobStatusResponse cancelRecognizeJob(@PathVariable String jobId) {
    return ocrJobService.cancel(jobId);
  }
}
