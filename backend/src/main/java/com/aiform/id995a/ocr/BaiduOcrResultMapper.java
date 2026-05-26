package com.aiform.id995a.ocr;

import com.aiform.id995a.review.EngineStatus;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BaiduOcrResultMapper {

  private final ObjectMapper objectMapper;

  public BaiduOcrResultMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public OcrDemoResponse toResponse(
      String filename,
      List<RenderedOcrPage> renderedPages,
      List<String> rawJsonByPage
  ) throws IOException {
    if (renderedPages.size() != rawJsonByPage.size()) {
      throw new IOException("Rendered page count does not match Baidu OCR response count.");
    }

    List<OcrPage> pages = new ArrayList<>();
    int totalWords = 0;
    for (int index = 0; index < renderedPages.size(); index += 1) {
      RenderedOcrPage renderedPage = renderedPages.get(index);
      JsonNode root = objectMapper.readTree(rawJsonByPage.get(index));
      JsonNode words = root.path("words_result");
      if (!words.isArray()) {
        throw new IOException("Baidu OCR response missing words_result.");
      }
      List<OcrTextBlock> blocks = extractBlocks(words);
      totalWords += blocks.size();
      String markdown = toMarkdown(blocks);
      pages.add(new OcrPage(
          renderedPage.page(),
          renderedPage.sourceImageDataUrl(),
          renderedPage.imageWidth(),
          renderedPage.imageHeight(),
          markdown,
          OcrTextHighlighter.toLines(markdown),
          blocks,
          List.of()
      ));
    }

    List<OcrPage> immutablePages = List.copyOf(pages);
    EngineStatus status = new EngineStatus(
        "Baidu OCR full-page document text",
        false,
        List.of(
            "Baidu OCR returned " + totalWords + " text line(s) across " + immutablePages.size() + " page(s).",
            "Document OCR mode returns full-page text only; no field inference, fixed form field list, or template coordinate boxes were used."
        )
    );
    return new OcrDemoResponse(
        filename == null || filename.isBlank() ? "uploaded-document" : filename,
        "Baidu OCR accurate",
        immutablePages.size(),
        immutablePages,
        List.of(),
        status,
        NullNode.getInstance(),
        ""
    );
  }

  private List<OcrTextBlock> extractBlocks(JsonNode words) {
    List<OcrTextBlock> blocks = new ArrayList<>();
    for (JsonNode word : words) {
      String text = word.path("words").asText("").trim();
      if (text.isBlank()) {
        continue;
      }
      blocks.add(new OcrTextBlock(
          "text",
          text,
          extractBbox(word.path("location")),
          OcrTextHighlighter.hasLikelyUserInput(text)
      ));
    }
    return List.copyOf(blocks);
  }

  private List<Integer> extractBbox(JsonNode location) {
    if (!location.isObject()) {
      return List.of();
    }
    int left = location.path("left").asInt();
    int top = location.path("top").asInt();
    int width = Math.max(0, location.path("width").asInt());
    int height = Math.max(0, location.path("height").asInt());
    return List.of(left, top, left + width, top + height);
  }

  private String toMarkdown(List<OcrTextBlock> blocks) {
    StringBuilder builder = new StringBuilder();
    for (OcrTextBlock block : blocks) {
      if (!builder.isEmpty()) {
        builder.append('\n');
      }
      builder.append(block.content());
    }
    return builder.toString();
  }
}
