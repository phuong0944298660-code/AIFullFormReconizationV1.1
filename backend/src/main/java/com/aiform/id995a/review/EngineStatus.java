package com.aiform.id995a.review;

import java.util.List;

public record EngineStatus(
    String extractionMode,
    boolean tesseractEnabled,
    List<String> messages
) {}
