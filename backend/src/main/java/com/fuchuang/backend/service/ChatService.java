package com.fuchuang.backend.service;

import com.fuchuang.backend.dto.ChatRequestDto;
import com.fuchuang.backend.dto.ChatResponseDto;
import com.fuchuang.backend.dto.CitationDto;
import com.fuchuang.backend.dto.OutputFileDto;
import com.fuchuang.backend.model.DocumentRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatService {
    private final KnowledgeBaseService knowledgeBaseService;
    private final ModelProviderService modelProviderService;
    private final OutputService outputService;

    public ChatService(KnowledgeBaseService knowledgeBaseService,
                       ModelProviderService modelProviderService,
                       OutputService outputService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelProviderService = modelProviderService;
        this.outputService = outputService;
    }

    public ChatResponseDto chat(ChatRequestDto request) {
        List<CitationDto> citations = knowledgeBaseService.search(request.message(), 6, request.sourceDocumentIds());
        List<String> contexts = citations.stream().map(CitationDto::snippet).toList();
        String answer = modelProviderService.complete(request.message(), contexts);

        List<OutputFileDto> outputs = new ArrayList<>();
        if (request.templateDocumentId() != null && !request.templateDocumentId().isBlank()) {
            DocumentRecord template = knowledgeBaseService.getDocument(request.templateDocumentId());
            List<DocumentRecord> sources = (request.sourceDocumentIds() == null ? List.<String>of() : request.sourceDocumentIds())
                    .stream().map(knowledgeBaseService::getDocument).toList();
            outputs.add(outputService.createTemplateResult(template, sources, request.message(), request.outputFormat()));
        } else if (request.createOutput() || request.saveOutputToKb()) {
            String ext = request.outputFormat() == null || request.outputFormat().isBlank() ? "txt" : request.outputFormat();
            outputs.add(outputService.createFromText("chat-output", ext, answer));
        }

        if (request.saveOutputToKb() && !outputs.isEmpty()) {
            outputService.saveToKnowledgeBase(outputs.get(0).id());
        }

        return new ChatResponseDto(answer, citations, outputs);
    }
}
