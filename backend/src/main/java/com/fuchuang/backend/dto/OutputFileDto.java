package com.fuchuang.backend.dto;

import java.time.Instant;

public record OutputFileDto(String id, String name, String extension, Instant createdAt) {
}
