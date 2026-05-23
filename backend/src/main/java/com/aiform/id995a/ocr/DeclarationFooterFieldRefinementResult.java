package com.aiform.id995a.ocr;

import com.fasterxml.jackson.databind.JsonNode;

public record DeclarationFooterFieldRefinementResult(
    JsonNode data,
    int attempted,
    int updated
) {
}
