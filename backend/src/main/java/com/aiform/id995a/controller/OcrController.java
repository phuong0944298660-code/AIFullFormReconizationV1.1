package com.aiform.id995a.controller;

import com.aiform.id995a.ocr.OcrDemoResponse;
import com.aiform.id995a.ocr.OcrDemoService;
import com.aiform.id995a.ocr.OcrJobService;
import com.aiform.id995a.ocr.OcrJobStatusResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class OcrController {

  private final OcrDemoService ocrDemoService;
  private final OcrJobService ocrJobService;

  public OcrController(OcrDemoService ocrDemoService, OcrJobService ocrJobService) {
    this.ocrDemoService = ocrDemoService;
    this.ocrJobService = ocrJobService;
  }

  @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OcrDemoResponse recognize(@RequestPart("file") MultipartFile file) throws IOException {
    return ocrDemoService.recognize(file.getOriginalFilename(), file.getContentType(), file.getBytes());
  }

  @PostMapping(value = "/ocr/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public OcrJobStatusResponse startRecognizeJob(@RequestPart("file") MultipartFile file) throws IOException {
    return ocrJobService.start(file.getOriginalFilename(), file.getContentType(), file.getBytes());
  }

  @GetMapping("/ocr/jobs/{jobId}")
  public OcrJobStatusResponse recognizeJobStatus(@PathVariable String jobId) {
    return ocrJobService.status(jobId);
  }
}
