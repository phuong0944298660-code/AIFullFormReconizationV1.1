package com.aiform.id995a.llm;

public record FieldCropTranscriptionRequest(
    int page,
    String path,
    String label,
    String currentValue,
    byte[] cropImageBytes,
    String cropImageDataUrl
) {

  public FieldCropTranscriptionRequest {
    path = path == null ? "" : path;
    label = label == null ? "" : label;
    currentValue = currentValue == null ? "" : currentValue;
    cropImageBytes = cropImageBytes == null ? new byte[0] : cropImageBytes.clone();
    cropImageDataUrl = cropImageDataUrl == null ? "" : cropImageDataUrl;
  }

  @Override
  public byte[] cropImageBytes() {
    return cropImageBytes.clone();
  }
}
