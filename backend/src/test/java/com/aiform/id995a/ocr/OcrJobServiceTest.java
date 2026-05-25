package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aiform.id995a.llm.ExtractionProgressListener;
import com.aiform.id995a.review.EngineStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OcrJobServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void cancelInterruptsRunningJobAndReportsCanceledStatus() throws Exception {
    BaiduOcrPageRenderer renderer = mock(BaiduOcrPageRenderer.class);
    OcrDemoService demoService = mock(OcrDemoService.class);
    TemplateDetectionService templateDetectionService = mock(TemplateDetectionService.class);
    CountDownLatch recognitionStarted = new CountDownLatch(1);
    CountDownLatch recognitionInterrupted = new CountDownLatch(1);
    RenderedOcrPage page = new RenderedOcrPage(
        1,
        new byte[] {1, 2, 3},
        "data:image/png;base64,AAA=",
        10,
        10
    );
    when(renderer.render(anyString(), anyString(), any())).thenReturn(List.of(page));
    when(templateDetectionService.detect(anyString(), anyString(), any(), anyList()))
        .thenReturn(new DocumentTemplate("id988a_2024_06", "ID 988A (06/2024)", 1, 98, "test", "hash"));
    when(demoService.normalizeFilename(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(demoService.recognizeRendered(anyString(), anyList(), any(ExtractionProgressListener.class), any(), any(DocumentTemplate.class)))
        .thenAnswer(invocation -> {
          recognitionStarted.countDown();
          try {
            Thread.sleep(30_000);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recognitionInterrupted.countDown();
            throw new IOException("interrupted", exception);
          }
          throw new IOException("test did not cancel the job");
        });

    OcrJobService service = new OcrJobService(renderer, demoService, templateDetectionService);
    OcrJobStatusResponse started = service.start("sample.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));
    assertThat(recognitionStarted.await(2, TimeUnit.SECONDS)).isTrue();

    OcrJobStatusResponse canceled = service.cancel(started.jobId());

    assertThat(canceled.status()).isEqualTo("canceled");
    assertThat(canceled.error()).isEmpty();
    assertThat(canceled.message()).contains("取消");
    assertThat(recognitionInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(service.status(started.jobId()).status()).isEqualTo("canceled");
  }

  @Test
  void reportsPostProcessingProgressBeforeFinalResult() throws Exception {
    BaiduOcrPageRenderer renderer = mock(BaiduOcrPageRenderer.class);
    OcrDemoService demoService = mock(OcrDemoService.class);
    TemplateDetectionService templateDetectionService = mock(TemplateDetectionService.class);
    CountDownLatch postProcessingStarted = new CountDownLatch(1);
    CountDownLatch releasePostProcessing = new CountDownLatch(1);
    RenderedOcrPage page1 = new RenderedOcrPage(
        1,
        new byte[] {1},
        "data:image/png;base64,AAA=",
        10,
        10
    );
    RenderedOcrPage page2 = new RenderedOcrPage(
        2,
        new byte[] {2},
        "data:image/png;base64,BBB=",
        10,
        10
    );
    when(renderer.render(anyString(), anyString(), any())).thenReturn(List.of(page1, page2));
    when(templateDetectionService.detect(anyString(), anyString(), any(), anyList()))
        .thenReturn(new DocumentTemplate("id988a_2024_06", "ID 988A (06/2024)", 2, 98, "test", "hash"));
    when(demoService.normalizeFilename(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    when(demoService.recognizeRendered(anyString(), anyList(), any(ExtractionProgressListener.class), any(), any(DocumentTemplate.class)))
        .thenAnswer(invocation -> {
          ExtractionProgressListener listener = invocation.getArgument(2);
          listener.pageStarted(1);
          listener.pageCompleted(1);
          listener.pageStarted(2);
          listener.pageCompleted(2);
          listener.postProcessingStep("crop_review", "crop review is running", 86);
          postProcessingStarted.countDown();
          assertThat(releasePostProcessing.await(2, TimeUnit.SECONDS)).isTrue();
          return new OcrDemoResponse(
              "sample.pdf",
              "local",
              2,
              List.of(),
              List.of(),
              new EngineStatus("local", false, List.of()),
              objectMapper.createObjectNode(),
              ""
          );
        });

    OcrJobService service = new OcrJobService(renderer, demoService, templateDetectionService);
    OcrJobStatusResponse started = service.start("sample.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));
    assertThat(postProcessingStarted.await(2, TimeUnit.SECONDS)).isTrue();

    OcrJobStatusResponse status = service.status(started.jobId());

    assertThat(status.status()).isEqualTo("post_processing");
    assertThat(status.progress()).isEqualTo(86);
    assertThat(status.completedPages()).isEqualTo(2);
    assertThat(status.message()).isEqualTo("crop review is running");
    assertThat(status.result()).isNull();

    releasePostProcessing.countDown();
    for (int attempt = 0; attempt < 20; attempt += 1) {
      if ("completed".equals(service.status(started.jobId()).status())) {
        break;
      }
      Thread.sleep(50);
    }
    OcrJobStatusResponse completed = service.status(started.jobId());
    assertThat(completed.status()).isEqualTo("completed");
    assertThat(completed.progress()).isEqualTo(100);
  }
}
