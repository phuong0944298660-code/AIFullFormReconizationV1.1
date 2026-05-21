package com.aiform.id995a.llm;

import java.util.List;

public record LlmModelOptionsResponse(
    String defaultModelId,
    List<LlmModelOption> models
) {}
