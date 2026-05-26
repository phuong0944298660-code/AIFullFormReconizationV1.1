package com.aiform.id995a.rag;

import java.util.List;

public record RetrievalStatus(
    String mode,
    boolean vectorEnabled,
    boolean vectorAvailable,
    String embeddingModel,
    int candidateCount,
    List<String> messages
) {}
