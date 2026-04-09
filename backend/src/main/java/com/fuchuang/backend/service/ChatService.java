package com.fuchuang.backend.service;

import com.fuchuang.backend.dto.ChatRequestDto;
import com.fuchuang.backend.dto.ChatResponseDto;
import com.fuchuang.backend.dto.CitationDto;
import com.fuchuang.backend.dto.OutputFileDto;
import com.fuchuang.backend.model.DocumentRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        List<String> trace = new ArrayList<>();
        trace.add("开始执行任务编排");
        trace.add("检索命中片段: " + citations.size());

        Map<String, String> mappedFields = new LinkedHashMap<>();
        List<OutputFileDto> outputs = new ArrayList<>();

        if (request.templateDocumentId() != null && !request.templateDocumentId().isBlank()) {
            DocumentRecord template = knowledgeBaseService.getDocument(request.templateDocumentId());
            List<DocumentRecord> sources = (request.sourceDocumentIds() == null ? List.<String>of() : request.sourceDocumentIds())
                    .stream().map(knowledgeBaseService::getDocument).toList();

            OutputService.TemplateTaskResult templateResult =
                    outputService.createTemplateResultDetailed(template, sources, request.message(), request.outputFormat());
            outputs.add(templateResult.output());
            mappedFields.putAll(templateResult.mappedFields());
            trace.addAll(templateResult.trace());

            answer = answer + "\n\n模板填充完成，映射字段数: " + mappedFields.size();
        } else if (request.createOutput() || request.saveOutputToKb()) {
            String ext = request.outputFormat() == null || request.outputFormat().isBlank() ? "txt" : request.outputFormat();
            outputs.add(outputService.createFromText("chat-output", ext, answer));
            trace.add("已生成输出文件: 1");
        }

        if (request.saveOutputToKb() && !outputs.isEmpty()) {
            outputService.saveToKnowledgeBase(outputs.get(0).id());
            trace.add("输出已保存到知识库");
        }

        trace.add("任务完成");
        return new ChatResponseDto(answer, citations, outputs, trace, mappedFields);
    }
}
