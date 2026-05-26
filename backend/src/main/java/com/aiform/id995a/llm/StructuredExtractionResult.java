package com.aiform.id995a.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record StructuredExtractionResult(
    JsonNode data,
    String rawText,
    String model
) {}
