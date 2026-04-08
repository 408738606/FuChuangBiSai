package com.fuchuang.backend.dto;

import java.time.Instant;

public record DocumentDto(String id, String name, String extension, Instant uploadedAt, int chunkCount) {
}
