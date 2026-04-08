package com.fuchuang.backend.dto;

import java.util.List;

public record ChatResponseDto(
        String answer,
        List<CitationDto> citations,
        List<OutputFileDto> outputs
) {
}
