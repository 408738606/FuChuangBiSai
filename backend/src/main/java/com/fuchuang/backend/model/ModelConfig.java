package com.fuchuang.backend.model;

public record ModelConfig(
        String mode,
        String apiBaseUrl,
        String apiKey,
        String modelName
) {
    public ModelConfig {
        if (mode == null || mode.isBlank()) {
            mode = "LOCAL";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "local-rag-agent";
        }
    }
}
