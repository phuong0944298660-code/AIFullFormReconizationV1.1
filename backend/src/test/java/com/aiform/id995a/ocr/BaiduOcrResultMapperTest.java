package com.aiform.id995a.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaiduOcrResultMapperTest {

  private final BaiduOcrResultMapper mapper = new BaiduOcrResultMapper(
      new ObjectMapper()
  );

  @Test
  void mapsBaiduWordsIntoPageSnapshotLinesBlocksAndNoAutoFields() throws Exception {
    RenderedOcrPage page = new RenderedOcrPage(
        1,
        "data:image/png;base64,page-one",
        1000,
        1400
    );
    String rawJson = """
        {
          "words_result_num": 3,
          "words_result": [
            {
              "words": "Surname in English CHAN",
              "location": {"left": 10, "top": 20, "width": 220, "height": 30}
            },
            {
              "words": "Date of birth 20-04-1998",
              "location": {"left": 10, "top": 65, "width": 240, "height": 30}
            },
            {
              "words": "Signature of applicant WONG",
              "location": {"left": 10, "top": 110, "width": 260, "height": 30}
            }
          ]
        }
        """;

    OcrDemoResponse response = mapper.toResponse(
        "sample.pdf",
        List.of(page),
        List.of(rawJson)
    );

    assertThat(response.model()).isEqualTo("Baidu OCR accurate");
    assertThat(response.pageCount()).isEqualTo(1);
    assertThat(response.pages()).hasSize(1);
    assertThat(response.pages().get(0).sourceImageDataUrl()).isEqualTo("data:image/png;base64,page-one");
    assertThat(response.pages().get(0).lines()).hasSize(3);
    assertThat(response.pages().get(0).blocks().get(0).bbox()).containsExactly(10, 20, 230, 50);
    assertThat(response.extractedFields()).isEmpty();
    assertThat(response.engineStatus().messages())
        .contains("Document OCR mode returns full-page text only; no field inference, fixed form field list, or template coordinate boxes were used.");
  }
}
