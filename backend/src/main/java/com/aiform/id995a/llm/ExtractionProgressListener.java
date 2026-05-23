package com.aiform.id995a.llm;

public interface ExtractionProgressListener {

  ExtractionProgressListener NOOP = new ExtractionProgressListener() {};

  default void pageStarted(int page) {}

  default void pageAttemptStarted(int page, int attempt, String reason) {}

  default void pageAttemptCompleted(int page, int attempt, String reason, long elapsedMillis) {}

  default void pageAttemptFailed(int page, int attempt, String reason, long elapsedMillis, String message) {}

  default void pageCompleted(int page) {}

  default void pageFailed(int page, String message) {}

  default void postProcessingStep(String stage, String message, int progress) {}
}
