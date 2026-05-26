package com.aiform.id995a.ocr;

import java.io.IOException;

@FunctionalInterface
public interface BaiduOcrGateway {
  String recognizePng(byte[] pagePngBytes) throws IOException;
}
