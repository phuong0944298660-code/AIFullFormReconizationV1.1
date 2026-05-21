package com.aiform.id995a.llm;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LlmModelRegistry {

  public static final String DEFAULT_MODEL_ID = "local-qwen3.6-35b-a3b";
  public static final String DASHSCOPE_MODEL_ID = "dashscope-qwen3.6-35b-a3b";

  private final LlmProperties llmProperties;
  private final DashScopeProperties dashScopeProperties;

  public LlmModelRegistry(LlmProperties llmProperties, DashScopeProperties dashScopeProperties) {
    this.llmProperties = llmProperties;
    this.dashScopeProperties = dashScopeProperties;
  }

  public LlmModelOptionsResponse options() {
    return new LlmModelOptionsResponse(DEFAULT_MODEL_ID, profiles().stream()
        .map(LlmModelProfile::toOption)
        .toList());
  }

  public LlmModelProfile resolve(String modelId) {
    String normalized = modelId == null || modelId.isBlank() ? DEFAULT_MODEL_ID : modelId.trim();
    return profiles().stream()
        .filter(profile -> profile.id().equals(normalized))
        .findFirst()
        .map(this::requireAvailable)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown LLM model: " + normalized));
  }

  public LlmModelProfile defaultProfile() {
    return resolve(DEFAULT_MODEL_ID);
  }

  private LlmModelProfile requireAvailable(LlmModelProfile profile) {
    if (!profile.available()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, profile.label() + " has no API key configured.");
    }
    return profile;
  }

  private List<LlmModelProfile> profiles() {
    return List.of(
        new LlmModelProfile(
            DEFAULT_MODEL_ID,
            "Qwen3.6-35B-A3B 视觉结构化",
            blank(llmProperties.model()) ? "Qwen3.6-35B-A3B" : llmProperties.model(),
            "OpenAI-compatible local gateway",
            blank(llmProperties.baseUrl()) ? "https://apie.zhisuaninfo.com/v1" : llmProperties.baseUrl(),
            llmProperties.apiKey(),
            false,
            true,
            blank(llmProperties.apiKey()) ? "缺少 LLM_API_KEY" : ""
        ),
        new LlmModelProfile(
            DASHSCOPE_MODEL_ID,
            "Qwen3.6-35B-A3B（官方原生）",
            blank(dashScopeProperties.model()) ? "qwen3.6-35b-a3b" : dashScopeProperties.model(),
            "DashScope OpenAI-compatible",
            blank(dashScopeProperties.baseUrl())
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                : dashScopeProperties.baseUrl(),
            dashScopeProperties.apiKey(),
            dashScopeProperties.enableThinking(),
            false,
            blank(dashScopeProperties.apiKey()) ? "缺少 DASHSCOPE_API_KEY" : ""
        )
    );
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
