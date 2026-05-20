package com.aiform.id995a.review;

import java.util.Map;

public record FormReviewInput(
    Map<String, String> metadata,
    FieldEvidence fields,
    AttachmentEvidence attachments
) {

  public String metadataValue(String key) {
    return metadata.getOrDefault(key, "");
  }
}
