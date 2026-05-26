package com.aiform.id995a.ocr;

import java.util.List;

public record OcrTextLine(
    int lineNumber,
    List<OcrTextSpan> spans,
    boolean hasUserInput
) {}
