package com.aiform.id995a.ocr;

import com.aiform.id995a.llm.ExtractionProgressListener;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OcrJobService {

  private final BaiduOcrPageRenderer pageRenderer;
  private final OcrDemoService ocrDemoService;
  private final ConcurrentMap<String, OcrJobState> jobs = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public OcrJobService(BaiduOcrPageRenderer pageRenderer, OcrDemoService ocrDemoService) {
    this.pageRenderer = pageRenderer;
    this.ocrDemoService = ocrDemoService;
  }

  public OcrJobStatusResponse start(String filename, String contentType, byte[] fileBytes) {
    String jobId = UUID.randomUUID().toString();
    OcrJobState state = new OcrJobState(jobId, ocrDemoService.normalizeFilename(filename));
    jobs.put(jobId, state);
    executor.submit(() -> runJob(state, filename, contentType, fileBytes));
    return state.snapshot();
  }

  public OcrJobStatusResponse status(String jobId) {
    OcrJobState state = jobs.get(jobId);
    if (state == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OCR job not found.");
    }
    return state.snapshot();
  }

  private void runJob(OcrJobState state, String filename, String contentType, byte[] fileBytes) {
    try {
      state.markRendering();
      List<RenderedOcrPage> pages = pageRenderer.render(filename, contentType, fileBytes);
      state.initializePages(pages);
      OcrDemoResponse result = ocrDemoService.recognizeRendered(
          state.filename(),
          pages,
          state.progressListener()
      );
      state.markCompleted(result);
    } catch (IOException | RuntimeException exception) {
      state.markFailed(exception.getMessage());
    }
  }

  private static final class OcrJobState {

    private final String jobId;
    private final String filename;
    private final Map<Integer, MutablePageProgress> pages = new LinkedHashMap<>();
    private String status = "queued";
    private String message = "任务已创建，等待开始处理。";
    private String error = "";
    private OcrDemoResponse result;

    private OcrJobState(String jobId, String filename) {
      this.jobId = jobId;
      this.filename = filename;
    }

    private String filename() {
      return filename;
    }

    private synchronized void markRendering() {
      status = "rendering";
      message = "正在渲染源文件页面快照。";
    }

    private synchronized void initializePages(List<RenderedOcrPage> renderedPages) {
      pages.clear();
      renderedPages.stream()
          .sorted(Comparator.comparingInt(RenderedOcrPage::page))
          .forEach(page -> pages.put(
              page.page(),
              new MutablePageProgress(page.page(), "pending", 0, "等待识别", "等待 Qwen 开始识别。")
          ));
      status = "running";
      message = "已生成页面快照，正在按页并发调用 Qwen。";
    }

    private ExtractionProgressListener progressListener() {
      return new ExtractionProgressListener() {
        @Override
        public void pageStarted(int page) {
          OcrJobState.this.pageStarted(page);
        }

        @Override
        public void pageCompleted(int page) {
          OcrJobState.this.pageCompleted(page);
        }

        @Override
        public void pageFailed(int page, String message) {
          OcrJobState.this.pageFailed(page, message);
        }
      };
    }

    private synchronized void pageStarted(int page) {
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "running";
      progress.percent = 0;
      progress.stage = "Qwen 识别中";
      progress.message = "正在识别第 " + page + " 页。";
      status = "running";
      message = "正在识别第 " + page + " 页。";
    }

    private synchronized void pageCompleted(int page) {
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "completed";
      progress.percent = 100;
      progress.stage = "已完成";
      progress.message = "第 " + page + " 页识别完成。";
      status = "running";
      message = "已完成 " + completedPages() + " / " + pages.size() + " 页。";
    }

    private synchronized void pageFailed(int page, String failureMessage) {
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "failed";
      progress.percent = 100;
      progress.stage = "识别失败";
      progress.message = failureMessage == null || failureMessage.isBlank()
          ? "第 " + page + " 页识别失败。"
          : failureMessage;
      status = "running";
      message = "第 " + page + " 页识别失败。";
      markFailedIfAllPagesTerminal();
    }

    private synchronized void markCompleted(OcrDemoResponse completedResult) {
      result = completedResult;
      for (MutablePageProgress page : pages.values()) {
        if (!"failed".equals(page.status) && !"completed".equals(page.status)) {
          page.status = "completed";
          page.percent = 100;
          page.stage = "已完成";
          page.message = "第 " + page.page + " 页识别完成。";
        }
      }
      status = "completed";
      message = "全部页面识别完成。";
      error = "";
    }

    private synchronized void markFailed(String failureMessage) {
      status = "failed";
      error = failureMessage == null || failureMessage.isBlank()
          ? "OCR job failed."
          : failureMessage;
      message = error;
    }

    private synchronized OcrJobStatusResponse snapshot() {
      List<OcrJobPageProgress> pageSnapshots = pages.values().stream()
          .sorted(Comparator.comparingInt(page -> page.page))
          .map(MutablePageProgress::snapshot)
          .toList();
      return new OcrJobStatusResponse(
          jobId,
          status,
          pageSnapshots.size(),
          completedPages(),
          failedPages(),
          overallProgress(pageSnapshots),
          activePage(pageSnapshots),
          pageSnapshots,
          message,
          error,
          result
      );
    }

    private int completedPages() {
      return (int) pages.values().stream().filter(page -> "completed".equals(page.status)).count();
    }

    private int failedPages() {
      return (int) pages.values().stream().filter(page -> "failed".equals(page.status)).count();
    }

    private void markFailedIfAllPagesTerminal() {
      if (pages.isEmpty() || failedPages() == 0 || result != null) {
        return;
      }
      boolean allTerminal = pages.values().stream()
          .allMatch(page -> "completed".equals(page.status) || "failed".equals(page.status));
      if (allTerminal) {
        status = "failed";
        error = pages.values().stream()
            .filter(page -> "failed".equals(page.status))
            .map(page -> page.message)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("部分页面识别失败。");
        message = error;
      }
    }

    private int overallProgress(List<OcrJobPageProgress> pageSnapshots) {
      if ("completed".equals(status)) {
        return 100;
      }
      if ("failed".equals(status)) {
        return pageProgressPercent(pageSnapshots);
      }
      if (pageSnapshots.isEmpty()) {
        return 0;
      }
      return pageProgressPercent(pageSnapshots);
    }

    private int pageProgressPercent(List<OcrJobPageProgress> pageSnapshots) {
      if (pageSnapshots.isEmpty()) {
        return 0;
      }
      long done = pageSnapshots.stream()
          .filter(page -> "completed".equals(page.status()) || "failed".equals(page.status()))
          .count();
      return (int) Math.round(done * 100.0 / pageSnapshots.size());
    }

    private Integer activePage(List<OcrJobPageProgress> pageSnapshots) {
      return pageSnapshots.stream()
          .filter(page -> "running".equals(page.status()))
          .map(OcrJobPageProgress::page)
          .findFirst()
          .orElse(null);
    }
  }

  private static final class MutablePageProgress {

    private final int page;
    private String status;
    private int percent;
    private String stage;
    private String message;

    private MutablePageProgress(int page, String status, int percent, String stage, String message) {
      this.page = page;
      this.status = status;
      this.percent = percent;
      this.stage = stage;
      this.message = message;
    }

    private OcrJobPageProgress snapshot() {
      return new OcrJobPageProgress(page, status, percent, stage, message);
    }
  }
}
