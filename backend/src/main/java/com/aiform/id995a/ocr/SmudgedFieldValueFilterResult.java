package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;

public record SmudgedFieldValueFilterResult(
    JsonNode data,
    int filtered
) {}
