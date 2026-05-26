package com.aiform.id995a.rag;

import java.util.List;

public record RetrievalResult(
    List<RuleEvidence> evidence,
    RetrievalStatus status
) {}
