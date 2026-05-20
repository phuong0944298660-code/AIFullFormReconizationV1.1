package com.aiform.id995a.llm;

public interface ExtractionProgressListener {

  ExtractionProgressListener NOOP = new ExtractionProgressListener() {};

  default void pageStarted(int page) {}

  default void pageCompleted(int page) {}

  default void pageFailed(int page, String message) {}
}
