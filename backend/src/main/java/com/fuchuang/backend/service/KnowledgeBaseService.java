package com.fuchuang.backend.service;

import com.fuchuang.backend.config.AppProperties;
import com.fuchuang.backend.dto.CitationDto;
import com.fuchuang.backend.dto.DocumentDto;
import com.fuchuang.backend.model.ChunkRecord;
import com.fuchuang.backend.model.DocumentRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KnowledgeBaseService {
    private final FileStorageService storageService;
    private final DocumentParserService parserService;
    private final int chunkSize;

    private final Map<String, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final Map<String, List<ChunkRecord>> chunksByDocument = new ConcurrentHashMap<>();

    public KnowledgeBaseService(FileStorageService storageService, DocumentParserService parserService, AppProperties properties) {
        this.storageService = storageService;
        this.parserService = parserService;
        this.chunkSize = properties.chunkSize();
    }

    public DocumentDto upload(MultipartFile file) {
        Path path = storageService.saveUpload(file);
        String name = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = extensionOf(name);
        String docId = UUID.randomUUID().toString();
        List<String> parts = parserService.chunk(parserService.parse(path, ext), chunkSize);
        List<ChunkRecord> chunkRecords = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            chunkRecords.add(new ChunkRecord(docId, i, parts.get(i)));
        }
        chunksByDocument.put(docId, chunkRecords);
        DocumentRecord record = new DocumentRecord(docId, name, ext, path.toString(), Instant.now(), parts.size());
        documents.put(docId, record);
        return toDto(record);
    }

    public DocumentDto registerFile(Path path, String originalName) {
        String ext = extensionOf(originalName);
        String docId = UUID.randomUUID().toString();
        List<String> parts = parserService.chunk(parserService.parse(path, ext), chunkSize);
        List<ChunkRecord> chunkRecords = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            chunkRecords.add(new ChunkRecord(docId, i, parts.get(i)));
        }
        chunksByDocument.put(docId, chunkRecords);
        DocumentRecord record = new DocumentRecord(docId, originalName, ext, path.toString(), Instant.now(), parts.size());
        documents.put(docId, record);
        return toDto(record);
    }

    public List<DocumentDto> listDocuments() {
        return documents.values().stream()
                .sorted(Comparator.comparing(DocumentRecord::uploadedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    public DocumentRecord getDocument(String id) {
        DocumentRecord record = documents.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Document not found: " + id);
        }
        return record;
    }

    public String previewText(String id) {
        DocumentRecord record = getDocument(id);
        return parserService.parse(Path.of(record.storedPath()), record.extension());
    }

    public List<CitationDto> search(String query, int topK, List<String> limitDocIds) {
        List<String> tokens = tokenize(query);
        List<ScoredCitation> ranked = new ArrayList<>();
        for (DocumentRecord doc : documents.values()) {
            if (limitDocIds != null && !limitDocIds.isEmpty() && !limitDocIds.contains(doc.id())) {
                continue;
            }
            for (ChunkRecord chunk : chunksByDocument.getOrDefault(doc.id(), List.of())) {
                int score = score(chunk.text(), tokens);
                if (score > 0 || tokens.isEmpty()) {
                    ranked.add(new ScoredCitation(score, new CitationDto(doc.id(), doc.name(), truncate(chunk.text(), 240))));
                }
            }
        }
        return ranked.stream()
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .limit(topK)
                .map(ScoredCitation::citation)
                .toList();
    }

    private int score(String text, List<String> tokens) {
        int score = 0;
        String lowered = text.toLowerCase();
        for (String token : tokens) {
            if (lowered.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase().trim();
        List<String> tokens = new ArrayList<>();
        tokens.add(normalized);
        tokens.addAll(List.of(normalized.split("[\\s,，。:：;；]+")));
        return tokens.stream().filter(t -> !t.isBlank()).distinct().toList();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private DocumentDto toDto(DocumentRecord record) {
        return new DocumentDto(record.id(), record.name(), record.extension(), record.uploadedAt(), record.chunkCount());
    }

    private String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "txt" : fileName.substring(idx + 1).toLowerCase();
    }

    private record ScoredCitation(int score, CitationDto citation) {
    }
}
