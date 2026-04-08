package com.fuchuang.backend.service;

import com.fuchuang.backend.dto.OutputFileDto;
import com.fuchuang.backend.model.DocumentRecord;
import com.fuchuang.backend.model.OutputRecord;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OutputService {
    private final FileStorageService storageService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final Map<String, OutputRecord> outputs = new ConcurrentHashMap<>();

    public OutputService(FileStorageService storageService, KnowledgeBaseService knowledgeBaseService) {
        this.storageService = storageService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public OutputFileDto createFromText(String baseName, String extension, String content) {
        String ext = extension == null || extension.isBlank() ? "txt" : extension.toLowerCase();
        String fileName = baseName + "." + ext;
        byte[] bytes = switch (ext) {
            case "md", "txt" -> content.getBytes(StandardCharsets.UTF_8);
            case "xlsx" -> createXlsx(content);
            case "docx" -> createDocx(content);
            default -> content.getBytes(StandardCharsets.UTF_8);
        };

        Path saved = storageService.saveGenerated(fileName, bytes);
        OutputRecord record = new OutputRecord(UUID.randomUUID().toString(), fileName, ext, saved.toString(), Instant.now(), previewText(content));
        outputs.put(record.id(), record);
        return toDto(record);
    }

    public OutputFileDto createTemplateResult(DocumentRecord template, List<DocumentRecord> sources, String instruction, String extension) {
        String ext = extension == null || extension.isBlank() ? template.extension() : extension;
        String summary = buildSummary(sources, instruction);
        if ("xlsx".equalsIgnoreCase(ext)) {
            byte[] bytes = fillXlsxTemplate(template, summary);
            Path path = storageService.saveGenerated("template-result.xlsx", bytes);
            OutputRecord record = new OutputRecord(UUID.randomUUID().toString(), "template-result.xlsx", "xlsx", path.toString(), Instant.now(), summary);
            outputs.put(record.id(), record);
            return toDto(record);
        }
        if ("docx".equalsIgnoreCase(ext)) {
            byte[] bytes = fillDocxTemplate(template, summary);
            Path path = storageService.saveGenerated("template-result.docx", bytes);
            OutputRecord record = new OutputRecord(UUID.randomUUID().toString(), "template-result.docx", "docx", path.toString(), Instant.now(), summary);
            outputs.put(record.id(), record);
            return toDto(record);
        }
        return createFromText("template-result", "txt", summary);
    }

    public List<OutputFileDto> list() {
        return outputs.values().stream()
                .sorted(Comparator.comparing(OutputRecord::createdAt).reversed())
                .map(this::toDto)
                .toList();
    }

    public OutputRecord get(String id) {
        OutputRecord record = outputs.get(id);
        if (record == null) {
            throw new IllegalArgumentException("Output not found: " + id);
        }
        return record;
    }

    public String preview(String id) {
        OutputRecord record = get(id);
        if ("txt".equals(record.extension()) || "md".equals(record.extension())) {
            try {
                return Files.readString(Path.of(record.storedPath()), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        return record.preview();
    }

    public OutputFileDto saveToKnowledgeBase(String outputId) {
        OutputRecord record = get(outputId);
        Path copied = storageService.copyToUploads(Path.of(record.storedPath()), record.name());
        return new OutputFileDto(
                knowledgeBaseService.registerFile(copied, record.name()).id(),
                record.name(),
                record.extension(),
                Instant.now()
        );
    }

    private String buildSummary(List<DocumentRecord> sources, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务要求: ").append(instruction == null ? "" : instruction).append("\n");
        sb.append("来源文件:\n");
        for (DocumentRecord source : sources) {
            sb.append("- ").append(source.name()).append("\n");
            String text = knowledgeBaseService.previewText(source.id());
            sb.append(previewText(text)).append("\n\n");
        }
        return sb.toString();
    }

    private byte[] fillXlsxTemplate(DocumentRecord template, String summary) {
        try {
            Path path = Path.of(template.storedPath());
            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
                XSSFSheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : workbook.createSheet("结果");
                if (sheet.getRow(0) == null) sheet.createRow(0);
                sheet.getRow(0).createCell(0).setCellValue("自动填表结果");
                if (sheet.getRow(1) == null) sheet.createRow(1);
                sheet.getRow(1).createCell(0).setCellValue(summary);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            return createXlsx(summary);
        }
    }

    private byte[] fillDocxTemplate(DocumentRecord template, String summary) {
        try {
            Path path = Path.of(template.storedPath());
            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path))) {
                document.createParagraph().createRun().setText("自动填表结果:");
                document.createParagraph().createRun().setText(summary);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.write(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            return createDocx(summary);
        }
    }

    private byte[] createXlsx(String content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("结果");
            sheet.createRow(0).createCell(0).setCellValue("模型输出");
            sheet.createRow(1).createCell(0).setCellValue(content);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] createDocx(String content) {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(content);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String previewText(String text) {
        if (text == null) return "";
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }

    private OutputFileDto toDto(OutputRecord record) {
        return new OutputFileDto(record.id(), record.name(), record.extension(), record.createdAt());
    }
}
