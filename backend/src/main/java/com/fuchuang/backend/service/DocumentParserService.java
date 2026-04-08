package com.fuchuang.backend.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentParserService {

    public String parse(Path path, String extension) {
        Path safePath = normalizeReadablePath(path);
        String ext = extension.toLowerCase();
        try {
            return switch (ext) {
                case "txt", "md" -> Files.readString(safePath, StandardCharsets.UTF_8);
                case "docx" -> parseDocx(safePath);
                case "xlsx" -> parseXlsx(safePath);
                default -> "";
            };
        } catch (IOException e) {
            return "";
        }
    }

    public List<String> chunk(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        for (int i = 0; i < normalized.length(); i += chunkSize) {
            chunks.add(normalized.substring(i, Math.min(normalized.length(), i + chunkSize)));
        }
        return chunks;
    }

    private String parseDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append("\n"));
            return sb.toString();
        }
    }

    private String parseXlsx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sb.append("# ").append(sheet.getSheetName()).append("\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(cell.toString().trim());
                    }
                    if (!cells.isEmpty()) {
                        sb.append(String.join("\t", cells)).append("\n");
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    private Path normalizeReadablePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Invalid file path.");
        }
        return normalized;
    }
}
