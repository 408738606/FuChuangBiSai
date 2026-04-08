package com.fuchuang.backend.controller;

import com.fuchuang.backend.dto.DocumentDto;
import com.fuchuang.backend.model.DocumentRecord;
import com.fuchuang.backend.service.FileStorageService;
import com.fuchuang.backend.service.KnowledgeBaseService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStorageService fileStorageService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService, FileStorageService fileStorageService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.fileStorageService = fileStorageService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentDto upload(@RequestPart("file") MultipartFile file) {
        return knowledgeBaseService.upload(file);
    }

    @GetMapping("/documents")
    public List<DocumentDto> documents() {
        return knowledgeBaseService.listDocuments();
    }

    @GetMapping("/documents/{id}/preview")
    public ResponseEntity<String> preview(@PathVariable String id) {
        return ResponseEntity.ok(knowledgeBaseService.previewText(id));
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        DocumentRecord doc = knowledgeBaseService.getDocument(id);
        Resource resource = fileStorageService.asResource(doc.storedPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(doc.name(), StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
