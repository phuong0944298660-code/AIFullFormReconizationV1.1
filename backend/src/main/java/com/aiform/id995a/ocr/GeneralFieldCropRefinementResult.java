package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;

public record GeneralFieldCropRefinementResult(
    JsonNode data,
    int attempted,
    int updated
) {}
