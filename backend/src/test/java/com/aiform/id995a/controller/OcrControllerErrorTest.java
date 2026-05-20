package com.aiform.id995a.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aiform.id995a.llm.StructuredExtractionGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
@Import(OcrControllerErrorTest.FailingStructuredExtractionConfig.class)
class OcrControllerErrorTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void returnsReadableMessageWhenLlmCredentialsAreMissing() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "sample.png",
        "image/png",
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4XmP4z8AAAAMBAQD3A0FDAAAAAElFTkSuQmCC")
    );

    mockMvc.perform(multipart("/api/ocr").file(file))
        .andExpect(status().isBadGateway())
        .andExpect(content().string(containsString("Missing LLM API key")));
  }

  @Test
  void asyncJobTransitionsToFailedWhenPageRecognitionFails() throws Exception {
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "sample.png",
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
      if ("failed".equals(objectMapper.readTree(statusBody).path("status").asText())) {
        break;
      }
      Thread.sleep(25);
    }

    com.fasterxml.jackson.databind.JsonNode statusJson = objectMapper.readTree(statusBody);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("status").asText()).isEqualTo("failed");
    org.assertj.core.api.Assertions.assertThat(statusJson.path("progress").asInt()).isEqualTo(100);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("failedPages").asInt()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(statusJson.path("pages").get(0).path("status").asText()).isEqualTo("failed");
  }

  @TestConfiguration
  static class FailingStructuredExtractionConfig {
    @Bean
    @Primary
    StructuredExtractionGateway failingStructuredExtractionGateway() {
      return (filename, pages, progressListener) -> {
        for (com.aiform.id995a.ocr.RenderedOcrPage page : pages) {
          progressListener.pageStarted(page.page());
          progressListener.pageFailed(page.page(), "Missing LLM API key. Set LLM_API_KEY.");
        }
        throw new IOException("Missing LLM API key. Set LLM_API_KEY.");
      };
    }
  }
}
