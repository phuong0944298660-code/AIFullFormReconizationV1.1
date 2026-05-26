package com.aiform.id995a.llm;

import java.io.IOException;
import java.util.List;

public interface FieldCropTranscriptionGateway {

  List<FieldCropTranscriptionResult> transcribeFieldCrops(
      String filename,
      List<FieldCropTranscriptionRequest> crops,
      LlmModelProfile modelProfile
  ) throws IOException;
}
