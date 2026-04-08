package com.fuchuang.backend.model;

import java.time.Instant;

public record OutputRecord(
        String id,
        String name,
        String extension,
        String storedPath,
        Instant createdAt,
        String preview
) {
}
