package com.fuchuang.backend.model;

import java.time.Instant;

public record DocumentRecord(
        String id,
        String name,
        String extension,
        String storedPath,
        Instant uploadedAt,
        int chunkCount
) {
}
