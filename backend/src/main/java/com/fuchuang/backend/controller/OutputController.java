package com.fuchuang.backend.controller;

import com.fuchuang.backend.dto.OutputFileDto;
import com.fuchuang.backend.model.OutputRecord;
import com.fuchuang.backend.service.FileStorageService;
import com.fuchuang.backend.service.OutputService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/outputs")
public class OutputController {
    private final OutputService outputService;
    private final FileStorageService fileStorageService;

    public OutputController(OutputService outputService, FileStorageService fileStorageService) {
        this.outputService = outputService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping
    public List<OutputFileDto> list() {
        return outputService.list();
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<String> preview(@PathVariable String id) {
        return ResponseEntity.ok(outputService.preview(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        OutputRecord record = outputService.get(id);
        Resource resource = fileStorageService.asResource(record.storedPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(record.name(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/{id}/save-to-kb")
    public OutputFileDto saveToKb(@PathVariable String id) {
        return outputService.saveToKnowledgeBase(id);
    }
}
