package com.aiform.id995a.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiform.id995a.llm.StructuredExtractionGateway;
import com.aiform.id995a.llm.StructuredExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "rag.enabled=false",
    "llm.enabled=true",
    "llm.model=Qwen3.6-35B-A3B"
})
@AutoConfigureMockMvc
@Import(OcrControllerTest.FakeStructuredExtractionConfig.class)
class OcrControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void uploadsDocumentAndReturnsSplitScreenStructuredLlmPayload() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "id988a.png",
        "image/png",
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4XmP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC")
    );

    mockMvc.perform(multipart("/api/ocr").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.filename", equalTo("id988a.png")))
        .andExpect(jsonPath("$.pageCount", equalTo(1)))
        .andExpect(jsonPath("$.pages[0].page", equalTo(1)))
        .andExpect(jsonPath("$.pages[0].sourceImageDataUrl", startsWith("data:image/png;base64,")))
        .andExpect(jsonPath("$.pages[0].lines", hasSize(0)))
        .andExpect(jsonPath("$.pages[0].blocks", hasSize(0)))
        .andExpect(jsonPath("$.structuredData.page_1.part_2_personal_particulars.surname_en", equalTo("CHAN")))
        .andExpect(jsonPath("$.structuredData.page_1.part_2_personal_particulars.alias").doesNotExist())
        .andExpect(jsonPath("$.pages[0].structuredFields", hasSize(2)))
        .andExpect(jsonPath("$.pages[0].structuredFields[0].ocrStatus", equalTo("not_run")))
        .andExpect(jsonPath("$.pages[0].structuredFields[0].characters[0].status", equalTo("ok")))
        .andExpect(jsonPath("$.extractedFields", hasSize(0)))
        .andExpect(jsonPath("$.engineStatus.messages[1]", equalTo("Rendered page snapshots were sent directly to the multimodal LLM to find fields and filled regions; no preset field list or manual template coordinate boxes were used.")));
  }

  @Test
  void startsAsyncJobAndReportsRealPageProgress() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "id988a.png",
        "image/png",
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4XmP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC")
    );

    String startBody = mockMvc.perform(multipart("/api/ocr/jobs").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists())
        .andReturn()
        .getResponse()
        .getContentAsString();
    String jobId = objectMapper.readTree(startBody).path("jobId").asText();

    String statusBody = "";
    for (int attempt = 0; attempt < 20; attempt += 1) {
      statusBody = mockMvc.perform(get("/api/ocr/jobs/{jobId}", jobId))
          .andExpect(status().isOk())
          .andReturn()
          .getResponse()
          .getContentAsString();
      if ("completed".equals(objectMapper.readTree(statusBody).path("status").asText())) {
        break;
      }
      Thread.sleep(25);
    }

    com.fasterxml.jackson.databind.JsonNode statusJson = objectMapper.readTree(statusBody);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("status").asText()).isEqualTo("completed");
    org.assertj.core.api.Assertions.assertThat(statusJson.path("progress").asInt()).isEqualTo(100);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("completedPages").asInt()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("pages").get(0).path("status").asText()).isEqualTo("completed");
    org.assertj.core.api.Assertions.assertThat(statusJson.path("result").path("structuredData").path("page_1").path("part_2_personal_particulars").path("surname_en").asText())
        .isEqualTo("CHAN");
  }

  @TestConfiguration
  static class FakeStructuredExtractionConfig {
    @Bean
    @Primary
    StructuredExtractionGateway fakeStructuredExtractionGateway(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
      return (filename, pages, progressListener) -> {
        for (com.aiform.id995a.ocr.RenderedOcrPage page : pages) {
          progressListener.pageStarted(page.page());
          progressListener.pageCompleted(page.page());
        }
        return new StructuredExtractionResult(
            objectMapper.readTree("""
                {
                  "source_file": "id988a.png",
                  "total_pages": 1,
                  "page_1": {
                    "part_2_personal_particulars": {
                      "surname_en": "CHAN",
                      "alias": null
                    }
                  }
                }
                """),
            "{\"page_1\":{\"part_2_personal_particulars\":{\"surname_en\":\"CHAN\",\"alias\":null}}}",
            "Qwen3.6-35B-A3B"
        );
      };
    }
  }
}
