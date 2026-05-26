package com.aiform.id995a.llm;

import com.aiform.id995a.rules.RuleFinding;
import com.aiform.id995a.rules.RuleReview;
import com.aiform.id995a.rules.RuleSeverity;
import com.aiform.id995a.rag.RuleEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmConclusionService {

  private final LlmProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  @Autowired
  public LlmConclusionService(LlmProperties properties) {
    this(properties, HttpClient.newHttpClient(), new ObjectMapper());
  }

  LlmConclusionService(LlmProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
    this.properties = properties;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public LlmConclusion generate(RuleReview review, String filename) {
    return generate(review, filename, List.of());
  }

  public LlmConclusion generate(RuleReview review, String filename, List<RuleEvidence> evidence) {
    String fallback = deterministicConclusion(review, filename);
    if (!properties.enabled() || blank(properties.apiKey())) {
      return new LlmConclusion(false, "disabled", properties.model(), fallback);
    }

    try {
      String text = callModel(review, filename, evidence);
      if (blank(text)) {
        return new LlmConclusion(true, "empty_fallback", properties.model(), fallback);
      }
      String trimmed = text.trim();
      if (!usableConclusion(trimmed, review)) {
        return new LlmConclusion(true, "format_fallback", properties.model(), fallback);
      }
      return new LlmConclusion(true, "ok", properties.model(), trimmed);
    } catch (Exception exception) {
      return new LlmConclusion(true, "error_fallback: " + exception.getMessage(), properties.model(), fallback);
    }
  }

  private String callModel(RuleReview review, String filename, List<RuleEvidence> evidence) throws IOException, InterruptedException {
    String prompt = buildPrompt(review, filename, evidence);
    Map<String, Object> payload = Map.of(
        "model", properties.model(),
        "temperature", 0,
        "max_tokens", properties.maxTokens(),
        "enable_thinking", false,
        "messages", List.of(
            Map.of("role", "system", "content", "你是审核结论格式化器。只能整理输入内容，禁止添加建议、解决方案、表格、emoji、标题层级或未提供事实。输出必须是中文纯文本。"),
            Map.of("role", "user", "content", prompt)
        )
    );

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(trimTrailingSlash(properties.baseUrl()) + "/chat/completions"))
        .version(HttpClient.Version.HTTP_1_1)
        .timeout(Duration.ofSeconds(Math.max(5, properties.timeoutSeconds())))
        .header("Authorization", "Bearer " + properties.apiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("LLM HTTP " + response.statusCode());
    }

    JsonNode root = objectMapper.readTree(response.body());
    JsonNode content = root.at("/choices/0/message/content");
    if (!content.isMissingNode() && !content.isNull()) {
      return content.asText();
    }
    return "";
  }

  private String buildPrompt(RuleReview review, String filename, List<RuleEvidence> evidence) {
    StringBuilder builder = new StringBuilder();
    builder.append("请严格按以下格式输出纯文本，不要输出 Markdown 标题、表格、建议、解决方案、emoji 或额外解释。整体结论只能使用“通过”或“N处不通过”。\n\n");
    builder.append("文件名：").append(filename == null ? "未命名 PDF" : filename).append('\n');
    builder.append("整体结论：").append(review.verdict()).append('\n');
    builder.append("不通过理由：\n");
    for (RuleFinding finding : review.findings()) {
      if (finding.severity() == RuleSeverity.BLOCKING) {
        builder.append("- ")
            .append(finding.message())
            .append("（")
            .append(finding.ruleId())
            .append("，第 ")
            .append(finding.page())
            .append(" 页）")
            .append('\n');
      }
    }

    List<RuleFinding> manual = review.findings().stream()
        .filter(finding -> finding.severity() == RuleSeverity.MANUAL_REVIEW)
        .toList();
    if (!manual.isEmpty()) {
      builder.append("需人工复核：\n");
      for (RuleFinding finding : manual) {
        builder.append("- ")
            .append(finding.message())
            .append("（")
            .append(finding.ruleId())
            .append("，第 ")
            .append(finding.page())
            .append(" 页）")
            .append('\n');
      }
    }

    if (evidence != null && !evidence.isEmpty()) {
      builder.append("\n参考规则证据 ID：");
      for (RuleEvidence item : evidence) {
        builder.append(item.chunkId()).append(' ');
      }
    }
    return builder.toString();
  }

  private String deterministicConclusion(RuleReview review, String filename) {
    StringBuilder builder = new StringBuilder();
    if (review.passed()) {
      builder.append("整体结论：通过。");
    } else {
      builder.append("整体结论：").append(review.verdict()).append('。');
    }

    List<RuleFinding> blockers = review.findings().stream()
        .filter(finding -> finding.severity() == RuleSeverity.BLOCKING)
        .toList();
    if (!blockers.isEmpty()) {
      builder.append('\n').append("不通过理由：");
      for (RuleFinding blocker : blockers) {
        builder.append('\n')
            .append("- ")
            .append(blocker.message())
            .append("（")
            .append(blocker.ruleId())
            .append("，第 ")
            .append(blocker.page())
            .append(" 页）");
      }
    }

    List<RuleFinding> manual = review.findings().stream()
        .filter(finding -> finding.severity() == RuleSeverity.MANUAL_REVIEW)
        .toList();
    if (!manual.isEmpty()) {
      builder.append('\n').append("需人工复核：");
      for (RuleFinding finding : manual) {
        builder.append('\n')
            .append("- ")
            .append(finding.message())
            .append("（")
            .append(finding.ruleId())
            .append("）");
      }
    }

    return builder.toString();
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private boolean usableConclusion(String text, RuleReview review) {
    if (blank(text)) {
      return false;
    }
    if (!text.startsWith("整体结论：" + review.verdict())) {
      return false;
    }
    List<String> forbiddenMarkers = List.of(
        "```",
        "#",
        "|",
        "Markdown",
        "emoji",
        "以下是",
        "解决方案",
        "建议",
        "请根据"
    );
    return forbiddenMarkers.stream().noneMatch(text::contains);
  }

  private String trimTrailingSlash(String value) {
    if (blank(value)) {
      return "https://apie.zhisuaninfo.com/v1";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
