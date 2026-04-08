package com.fuchuang.backend.dto;

import java.util.List;

public record ChatRequestDto(
        String message,
        String outputFormat,
        boolean saveOutputToKb,
        boolean createOutput,
        String templateDocumentId,
        List<String> sourceDocumentIds
) {
}
