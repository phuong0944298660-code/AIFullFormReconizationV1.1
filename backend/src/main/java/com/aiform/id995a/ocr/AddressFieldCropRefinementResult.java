package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;

public record AddressFieldCropRefinementResult(
    JsonNode data,
    int attempted,
    int updated
) {}
