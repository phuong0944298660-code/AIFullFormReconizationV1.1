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
import java.util.concurrent.Future;
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
    return start(filename, contentType, fileBytes, null);
  }

  public OcrJobStatusResponse start(String filename, String contentType, byte[] fileBytes, String modelId) {
    String jobId = UUID.randomUUID().toString();
    OcrJobState state = new OcrJobState(jobId, ocrDemoService.normalizeFilename(filename));
    jobs.put(jobId, state);
    Future<?> future = executor.submit(() -> runJob(state, filename, contentType, fileBytes, modelId));
    state.attachFuture(future);
    return state.snapshot();
  }

  public OcrJobStatusResponse cancel(String jobId) {
    OcrJobState state = jobs.get(jobId);
    if (state == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OCR job not found.");
    }
    Future<?> future = state.markCanceled();
    if (future != null) {
      future.cancel(true);
    }
    return state.snapshot();
  }

  public OcrJobStatusResponse status(String jobId) {
    OcrJobState state = jobs.get(jobId);
    if (state == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OCR job not found.");
    }
    return state.snapshot();
  }

  private void runJob(OcrJobState state, String filename, String contentType, byte[] fileBytes, String modelId) {
    try {
      state.markRendering();
      List<RenderedOcrPage> pages = pageRenderer.render(filename, contentType, fileBytes);
      if (state.isCanceled()) {
        return;
      }
      state.initializePages(pages);
      OcrDemoResponse result = ocrDemoService.recognizeRendered(
          state.filename(),
          pages,
          state.progressListener(),
          modelId
      );
      if (state.isCanceled()) {
        return;
      }
      state.markCompleted(result);
    } catch (IOException | RuntimeException exception) {
      if (state.isCanceled()) {
        state.markCanceled();
      } else {
        state.markFailed(exception.getMessage());
      }
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
    private Future<?> future;
    private int postProcessingProgress;

    private OcrJobState(String jobId, String filename) {
      this.jobId = jobId;
      this.filename = filename;
    }

    private String filename() {
      return filename;
    }

    private synchronized void attachFuture(Future<?> future) {
      this.future = future;
      if (isCanceled()) {
        future.cancel(true);
      }
    }

    private synchronized boolean isCanceled() {
      return "canceled".equals(status);
    }

    private synchronized void markRendering() {
      if (isTerminal()) {
        return;
      }
      status = "rendering";
      message = "正在渲染源文件页面快照。";
    }

    private synchronized void initializePages(List<RenderedOcrPage> renderedPages) {
      if (isTerminal()) {
        return;
      }
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
        public void pageAttemptStarted(int page, int attempt, String reason) {
          OcrJobState.this.pageAttemptStarted(page, attempt, reason);
        }

        @Override
        public void pageAttemptCompleted(int page, int attempt, String reason, long elapsedMillis) {
          OcrJobState.this.pageAttemptCompleted(page, attempt, reason, elapsedMillis);
        }

        @Override
        public void pageAttemptFailed(int page, int attempt, String reason, long elapsedMillis, String message) {
          OcrJobState.this.pageAttemptFailed(page, attempt, reason, elapsedMillis, message);
        }

        @Override
        public void pageCompleted(int page) {
          OcrJobState.this.pageCompleted(page);
        }

        @Override
        public void pageFailed(int page, String message) {
          OcrJobState.this.pageFailed(page, message);
        }

        @Override
        public void postProcessingStep(String stage, String message, int progress) {
          OcrJobState.this.postProcessingStep(stage, message, progress);
        }
      };
    }

    private synchronized void pageStarted(int page) {
      if (isTerminal()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "running";
      progress.percent = 0;
      progress.stage = "Qwen 识别中";
      progress.message = "正在识别第 " + page + " 页。";
      progress.startedAtNanos = System.nanoTime();
      progress.completedAtNanos = 0;
      progress.currentAttemptStartedAtNanos = 0;
      progress.attempt = 0;
      progress.attemptReason = "";
      progress.lastAttemptMillis = 0;
      status = "running";
      message = "正在识别第 " + page + " 页。";
    }

    private synchronized void pageAttemptStarted(int page, int attempt, String reason) {
      if (isTerminal()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      if (progress.startedAtNanos == 0) {
        progress.startedAtNanos = System.nanoTime();
      }
      progress.status = "running";
      progress.percent = 0;
      progress.stage = "Qwen 识别中";
      progress.attempt = Math.max(progress.attempt, attempt);
      progress.attemptReason = reason == null ? "" : reason;
      progress.currentAttemptStartedAtNanos = System.nanoTime();
      progress.message = "第 " + page + " 页第 " + attempt + " 次请求（" + attemptLabel(reason) + "）。";
      status = "running";
      message = progress.message;
    }

    private synchronized void pageAttemptCompleted(int page, int attempt, String reason, long elapsedMillis) {
      if (isTerminal()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.attempt = Math.max(progress.attempt, attempt);
      progress.attemptReason = reason == null ? "" : reason;
      progress.lastAttemptMillis = Math.max(0, elapsedMillis);
      progress.currentAttemptStartedAtNanos = 0;
      progress.message = "第 " + page + " 页第 " + attempt + " 次请求完成，用时 " + formatDuration(progress.lastAttemptMillis) + "。";
      status = "running";
      message = progress.message;
    }

    private synchronized void pageAttemptFailed(int page, int attempt, String reason, long elapsedMillis, String failureMessage) {
      if (isCanceled()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.attempt = Math.max(progress.attempt, attempt);
      progress.attemptReason = reason == null ? "" : reason;
      progress.lastAttemptMillis = Math.max(0, elapsedMillis);
      progress.currentAttemptStartedAtNanos = 0;
      progress.message = failureMessage == null || failureMessage.isBlank()
          ? "第 " + page + " 页第 " + attempt + " 次请求失败。"
          : failureMessage;
      status = "running";
      message = progress.message;
    }

    private synchronized void pageCompleted(int page) {
      if (isTerminal()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "completed";
      progress.percent = 100;
      progress.stage = "已完成";
      progress.completedAtNanos = System.nanoTime();
      progress.currentAttemptStartedAtNanos = 0;
      progress.message = "第 " + page + " 页识别完成，用时 " + formatDuration(progress.elapsedMillis())
          + "，共 " + Math.max(1, progress.attempt) + " 次请求。";
      status = "running";
      message = "已完成 " + completedPages() + " / " + pages.size() + " 页。";
    }

    private synchronized void pageFailed(int page, String failureMessage) {
      if (isCanceled()) {
        return;
      }
      MutablePageProgress progress = pages.computeIfAbsent(
          page,
          key -> new MutablePageProgress(key, "pending", 0, "等待识别", "等待 Qwen 开始识别。")
      );
      progress.status = "failed";
      progress.percent = 100;
      progress.stage = "识别失败";
      progress.completedAtNanos = System.nanoTime();
      progress.currentAttemptStartedAtNanos = 0;
      progress.message = failureMessage == null || failureMessage.isBlank()
          ? "第 " + page + " 页识别失败。"
          : failureMessage;
      status = "running";
      message = "第 " + page + " 页识别失败。";
      markFailedIfAllPagesTerminal();
    }

    private synchronized void postProcessingStep(String stage, String stepMessage, int progressPercent) {
      if (isTerminal()) {
        return;
      }
      status = "post_processing";
      postProcessingProgress = clampPostProcessingProgress(progressPercent);
      message = stepMessage == null || stepMessage.isBlank()
          ? "正在复核识别结果。"
          : stepMessage;
    }

    private synchronized void markCompleted(OcrDemoResponse completedResult) {
      if (isTerminal()) {
        return;
      }
      result = completedResult;
      for (MutablePageProgress page : pages.values()) {
        if (!"failed".equals(page.status) && !"completed".equals(page.status)) {
          page.status = "completed";
          page.percent = 100;
          page.stage = "已完成";
          page.completedAtNanos = System.nanoTime();
          page.currentAttemptStartedAtNanos = 0;
          page.message = "第 " + page.page + " 页识别完成，用时 " + formatDuration(page.elapsedMillis())
              + "，共 " + Math.max(1, page.attempt) + " 次请求。";
        }
      }
      status = "completed";
      message = "全部页面识别完成。";
      error = "";
    }

    private synchronized void markFailed(String failureMessage) {
      if (isCanceled()) {
        return;
      }
      status = "failed";
      error = failureMessage == null || failureMessage.isBlank()
          ? "OCR job failed."
          : failureMessage;
      message = error;
    }

    private synchronized Future<?> markCanceled() {
      if ("completed".equals(status) || "failed".equals(status)) {
        return future;
      }
      status = "canceled";
      error = "";
      result = null;
      message = "识别任务已取消。";
      for (MutablePageProgress page : pages.values()) {
        if (!"completed".equals(page.status) && !"failed".equals(page.status)) {
          page.status = "canceled";
          page.percent = 100;
          page.stage = "已取消";
          page.completedAtNanos = System.nanoTime();
          page.currentAttemptStartedAtNanos = 0;
          page.message = "第 " + page.page + " 页识别已取消。";
        }
      }
      return future;
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
      if ("canceled".equals(status)) {
        return 100;
      }
      if ("post_processing".equals(status)) {
        return clampPostProcessingProgress(postProcessingProgress);
      }
      if (pageSnapshots.isEmpty()) {
        return 0;
      }
      return Math.min(99, pageProgressPercent(pageSnapshots));
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

    private String attemptLabel(String reason) {
      return switch (reason == null ? "" : reason) {
        case "initial" -> "首次请求";
        case "empty_retry" -> "空结果重试";
        default -> reason == null || reason.isBlank() ? "请求" : reason;
      };
    }

    private boolean isTerminal() {
      return "completed".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }

    private static int clampPostProcessingProgress(int progress) {
      return Math.max(0, Math.min(99, progress));
    }

    private static String formatDuration(long elapsedMillis) {
      long seconds = Math.max(elapsedMillis > 0 ? 1 : 0, elapsedMillis / 1000);
      long minutes = seconds / 60;
      long remainder = seconds % 60;
      if (minutes > 0) {
        return minutes + "分" + remainder + "秒";
      }
      return seconds + "秒";
    }
  }

  private static final class MutablePageProgress {

    private final int page;
    private String status;
    private int percent;
    private String stage;
    private String message;
    private long startedAtNanos;
    private long completedAtNanos;
    private long currentAttemptStartedAtNanos;
    private int attempt;
    private String attemptReason = "";
    private long lastAttemptMillis;

    private MutablePageProgress(int page, String status, int percent, String stage, String message) {
      this.page = page;
      this.status = status;
      this.percent = percent;
      this.stage = stage;
      this.message = message;
    }

    private OcrJobPageProgress snapshot() {
      return new OcrJobPageProgress(
          page,
          status,
          percent,
          stage,
          message,
          elapsedMillis(),
          attempt,
          attemptReason,
          lastAttemptMillis,
          currentAttemptMillis()
      );
    }

    private long elapsedMillis() {
      if (startedAtNanos == 0) {
        return 0;
      }
      long end = completedAtNanos == 0 ? System.nanoTime() : completedAtNanos;
      return Math.max(0, (end - startedAtNanos) / 1_000_000);
    }

    private long currentAttemptMillis() {
      if (!"running".equals(status) || currentAttemptStartedAtNanos == 0) {
        return 0;
      }
      return Math.max(0, (System.nanoTime() - currentAttemptStartedAtNanos) / 1_000_000);
    }
  }
}
