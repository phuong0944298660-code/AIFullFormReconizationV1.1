package com.aiform.id995a.review;

import com.aiform.id995a.llm.LlmConclusion;
import com.aiform.id995a.rag.RetrievalStatus;
import com.aiform.id995a.rag.RuleEvidence;
import com.aiform.id995a.rules.RuleFinding;
import java.util.List;

public record ReviewResponse(
    boolean passed,
    String verdict,
    List<String> overallReasons,
    List<RuleFinding> findings,
    List<DocumentAnnotation> annotations,
    List<ExtractedField> extractedFields,
    List<PageSnapshot> pageSnapshots,
    String snapshotDataUrl,
    EngineStatus engineStatus,
    LlmConclusion llmConclusion,
    List<RuleEvidence> retrievedRules,
    RetrievalStatus retrievalStatus,
    List<SourceReference> sources,
    String extractedTextPreview
) {}
