package com.fuchuang.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String storageRoot, int chunkSize) {
    public AppProperties {
        if (storageRoot == null || storageRoot.isBlank()) {
            storageRoot = "data";
        }
        if (chunkSize <= 0) {
            chunkSize = 500;
        }
    }
}
