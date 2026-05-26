package com.aiform.id995a.ocr;

import java.io.IOException;
import java.util.List;

public interface FieldRegionOcrGateway {

  default FieldRegionOcrResult recognize(byte[] cropImageBytes) throws IOException {
    List<FieldRegionOcrResult> results = recognizeBatch(List.of(cropImageBytes));
    return results.isEmpty() ? FieldRegionOcrResult.unavailable("not_run") : results.get(0);
  }

  List<FieldRegionOcrResult> recognizeBatch(List<byte[]> cropImageBytes) throws IOException;
}
