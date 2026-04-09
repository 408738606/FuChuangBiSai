package com.fuchuang.backend.dto;

import java.util.List;
import java.util.Map;

public record ChatResponseDto(
        String answer,
        List<CitationDto> citations,
        List<OutputFileDto> outputs,
        List<String> trace,
        Map<String, String> mappedFields
) {
}
