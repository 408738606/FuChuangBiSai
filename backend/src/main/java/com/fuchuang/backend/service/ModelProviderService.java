package com.fuchuang.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuchuang.backend.model.ModelConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ModelProviderService {
    private static final ModelConfig DEFAULT = new ModelConfig("LOCAL", "", "", "local-rag-agent");

    private final AtomicReference<ModelConfig> config = new AtomicReference<>(DEFAULT);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ModelConfig getConfig() {
        return config.get();
    }

    public ModelConfig updateConfig(ModelConfig next) {
        ModelConfig normalized = new ModelConfig(
                normalizeMode(next.mode()),
                nullToEmpty(next.apiBaseUrl()),
                nullToEmpty(next.apiKey()),
                nullToEmpty(next.modelName())
        );
        config.set(normalized);
        return normalized;
    }

    public String complete(String message, List<String> contexts) {
        ModelConfig current = config.get();
        if ("API".equalsIgnoreCase(current.mode()) && !current.apiBaseUrl().isBlank()) {
            String apiAnswer = callOpenAiCompatible(current, message, contexts);
            if (!apiAnswer.isBlank()) {
                return apiAnswer;
            }
        }
        return localComplete(message, contexts);
    }

    private String localComplete(String message, List<String> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("已基于本地知识库完成任务。\n");
        if (message != null && message.contains("填表")) {
            sb.append("已识别为模板填表任务，建议检查生成文件并二次确认映射字段。\n");
        }
        if (!contexts.isEmpty()) {
            sb.append("参考片段:\n");
            for (int i = 0; i < Math.min(3, contexts.size()); i++) {
                sb.append(i + 1).append(". ").append(contexts.get(i)).append("\n");
            }
        } else {
            sb.append("未命中知识库内容，请先上传文件后重试。");
        }
        return sb.toString().trim();
    }

    private String callOpenAiCompatible(ModelConfig current, String message, List<String> contexts) {
        try {
            String prompt = buildPrompt(message, contexts);
            String payload = objectMapper.createObjectNode()
                    .put("model", current.modelName().isBlank() ? "gpt-4o-mini" : current.modelName())
                    .set("messages", objectMapper.createArrayNode()
                            .add(objectMapper.createObjectNode().put("role", "system").put("content", "你是一个文件任务助手。"))
                            .add(objectMapper.createObjectNode().put("role", "user").put("content", prompt)))
                    .toString();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(current.apiBaseUrl().replaceAll("/$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));

            if (!current.apiKey().isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + current.apiKey());
            }

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode node = objectMapper.readTree(response.body());
                JsonNode content = node.path("choices").path(0).path("message").path("content");
                return content.isMissingNode() ? "" : content.asText("");
            }
            return "";
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private String buildPrompt(String message, List<String> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户请求:\n").append(message).append("\n\n");
        sb.append("知识库检索片段:\n");
        for (String c : contexts) {
            sb.append("- ").append(c).append("\n");
        }
        return sb.toString();
    }

    private String normalizeMode(String mode) {
        return "API".equalsIgnoreCase(mode) ? "API" : "LOCAL";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
