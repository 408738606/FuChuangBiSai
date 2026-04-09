package com.fuchuang.backend.service;

import com.fuchuang.backend.dto.OutputFileDto;
import com.fuchuang.backend.model.DocumentRecord;
import com.fuchuang.backend.model.OutputRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OutputService {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(.+?)}}|【(.+?)】|\\$\\{(.+?)}");

    private final FileStorageService storageService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FieldExtractionService fieldExtractionService;
    private final Map<String, OutputRecord> outputs = new ConcurrentHashMap<>();

    public OutputService(FileStorageService storageService,
                         KnowledgeBaseService knowledgeBaseService,
                         FieldExtractionService fieldExtractionService) {
        this.storageService = storageService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.fieldExtractionService = fieldExtractionService;
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

    public TemplateTaskResult createTemplateResultDetailed(DocumentRecord template,
                                                           List<DocumentRecord> sources,
                                                           String instruction,
                                                           String extension) {
        String ext = extension == null || extension.isBlank() ? template.extension() : extension.toLowerCase();
        FieldExtractionService.ExtractionResult extraction = fieldExtractionService.extractCandidates(sources, instruction);

        List<String> trace = new ArrayList<>(extraction.trace());
        Map<String, String> mappedFields = new LinkedHashMap<>();

        OutputFileDto output;
        if ("xlsx".equalsIgnoreCase(ext)) {
            byte[] bytes = fillXlsxTemplate(template, extraction.candidates(), instruction, mappedFields, trace);
            Path path = storageService.saveGenerated("template-result.xlsx", bytes);
            OutputRecord record = new OutputRecord(UUID.randomUUID().toString(), "template-result.xlsx", "xlsx", path.toString(), Instant.now(), buildSummary(mappedFields, trace));
            outputs.put(record.id(), record);
            output = toDto(record);
        } else if ("docx".equalsIgnoreCase(ext)) {
            byte[] bytes = fillDocxTemplate(template, extraction.candidates(), instruction, mappedFields, trace);
            Path path = storageService.saveGenerated("template-result.docx", bytes);
            OutputRecord record = new OutputRecord(UUID.randomUUID().toString(), "template-result.docx", "docx", path.toString(), Instant.now(), buildSummary(mappedFields, trace));
            outputs.put(record.id(), record);
            output = toDto(record);
        } else {
            String textResult = buildTextTaskResult(sources, instruction, extraction.candidates(), mappedFields, trace);
            output = createFromText("template-result", "txt", textResult);
        }

        trace.add("映射字段数: " + mappedFields.size());
        if (mappedFields.isEmpty()) {
            trace.add("未命中字段映射，建议补充更明确字段名或过滤条件。\n");
        }

        return new TemplateTaskResult(output, mappedFields, trace);
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

    private byte[] fillXlsxTemplate(DocumentRecord template,
                                    List<FieldExtractionService.Candidate> candidates,
                                    String instruction,
                                    Map<String, String> mappedFields,
                                    List<String> trace) {
        try {
            Path path = Path.of(template.storedPath());
            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
                for (Sheet sheet : workbook) {
                    mapByPlaceholders(sheet, candidates, instruction, mappedFields, trace);
                    mapByHeader(sheet, candidates, instruction, mappedFields, trace);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            trace.add("xlsx模板读取失败，降级为文本输出。原因: " + e.getMessage());
            return createXlsx(buildSummary(mappedFields, trace));
        }
    }

    private void mapByPlaceholders(Sheet sheet,
                                   List<FieldExtractionService.Candidate> candidates,
                                   String instruction,
                                   Map<String, String> mappedFields,
                                   List<String> trace) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                String raw = getCellString(cell);
                String replaced = replacePlaceholders(raw, candidates, instruction, mappedFields, trace);
                if (!raw.equals(replaced)) {
                    cell.setCellType(CellType.STRING);
                    cell.setCellValue(replaced);
                }
            }
        }
    }

    private void mapByHeader(Sheet sheet,
                             List<FieldExtractionService.Candidate> candidates,
                             String instruction,
                             Map<String, String> mappedFields,
                             List<String> trace) {
        Row header = sheet.getRow(0);
        if (header == null) {
            return;
        }
        Row target = sheet.getRow(1);
        if (target == null) {
            target = sheet.createRow(1);
        }
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell headerCell = header.getCell(c);
            String field = headerCell == null ? "" : getCellString(headerCell).trim();
            if (field.isBlank()) {
                continue;
            }
            Cell targetCell = target.getCell(c);
            String existing = targetCell == null ? "" : getCellString(targetCell).trim();
            if (!existing.isBlank()) {
                continue;
            }
            Optional<FieldExtractionService.Candidate> matched = fieldExtractionService.matchField(field, candidates, instruction);
            if (matched.isPresent() && scoreEnough(field, matched.get().key())) {
                if (targetCell == null) {
                    targetCell = target.createCell(c, CellType.STRING);
                }
                targetCell.setCellType(CellType.STRING);
                targetCell.setCellValue(matched.get().value());
                mappedFields.putIfAbsent(field, matched.get().value());
                trace.add("表头映射: [" + field + "] <- " + matched.get().key() + " (" + matched.get().sourceName() + ")");
            }
        }
    }

    private byte[] fillDocxTemplate(DocumentRecord template,
                                    List<FieldExtractionService.Candidate> candidates,
                                    String instruction,
                                    Map<String, String> mappedFields,
                                    List<String> trace) {
        try {
            Path path = Path.of(template.storedPath());
            try (XWPFDocument document = new XWPFDocument(Files.newInputStream(path))) {
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    if (paragraph.getRuns() == null || paragraph.getRuns().isEmpty()) continue;
                    String text = paragraph.getText();
                    String replaced = replacePlaceholders(text, candidates, instruction, mappedFields, trace);
                    if (!text.equals(replaced)) {
                        paragraph.getRuns().forEach(run -> run.setText("", 0));
                        paragraph.getRuns().get(0).setText(replaced, 0);
                    }
                }

                for (XWPFTable table : document.getTables()) {
                    table.getRows().forEach(row -> row.getTableCells().forEach(cell -> replaceCellText(cell, candidates, instruction, mappedFields, trace)));
                }

                if (mappedFields.isEmpty()) {
                    document.createParagraph().createRun().setText("自动映射未命中字段，请补充更明确筛选条件。\n");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                document.write(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            trace.add("docx模板读取失败，降级为文本输出。原因: " + e.getMessage());
            return createDocx(buildSummary(mappedFields, trace));
        }
    }

    private void replaceCellText(XWPFTableCell cell,
                                 List<FieldExtractionService.Candidate> candidates,
                                 String instruction,
                                 Map<String, String> mappedFields,
                                 List<String> trace) {
        String text = cell.getText();
        String replaced = replacePlaceholders(text, candidates, instruction, mappedFields, trace);
        if (!text.equals(replaced)) {
            cell.removeParagraph(0);
            cell.addParagraph().createRun().setText(replaced);
        }
    }

    private String replacePlaceholders(String raw,
                                       List<FieldExtractionService.Candidate> candidates,
                                       String instruction,
                                       Map<String, String> mappedFields,
                                       List<String> trace) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String field = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            Optional<FieldExtractionService.Candidate> matched = fieldExtractionService.matchField(field, candidates, instruction);
            String replacement = matched.filter(c -> scoreEnough(field, c.key())).map(FieldExtractionService.Candidate::value).orElse("[待确认]");
            matched.ifPresent(candidate -> {
                mappedFields.putIfAbsent(field, candidate.value());
                trace.add("占位符映射: [" + field + "] <- " + candidate.key() + " (" + candidate.sourceName() + ")");
            });
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String buildTextTaskResult(List<DocumentRecord> sources,
                                       String instruction,
                                       List<FieldExtractionService.Candidate> candidates,
                                       Map<String, String> mappedFields,
                                       List<String> trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务要求: ").append(instruction == null ? "" : instruction).append("\n");
        sb.append("来源文件:\n");
        for (DocumentRecord source : sources) {
            sb.append("- ").append(source.name()).append("\n");
        }
        sb.append("\n抽取字段样例:\n");
        fieldExtractionService.toDedupMap(candidates).entrySet().stream().limit(20)
                .forEach(entry -> {
                    mappedFields.putIfAbsent(entry.getKey(), entry.getValue());
                    sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                });
        sb.append("\n追踪:\n");
        trace.forEach(t -> sb.append("- ").append(t).append("\n"));
        return sb.toString();
    }

    private String buildSummary(Map<String, String> mappedFields, List<String> trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("自动填表执行完成\n");
        sb.append("已映射字段: ").append(mappedFields.size()).append("\n");
        mappedFields.forEach((k, v) -> sb.append("- ").append(k).append(" = ").append(previewText(v)).append("\n"));
        sb.append("执行追踪:\n");
        trace.forEach(step -> sb.append("* ").append(step).append("\n"));
        return sb.toString();
    }

    private boolean scoreEnough(String expected, String actual) {
        String e = normalize(expected);
        String a = normalize(actual);
        if (e.isBlank() || a.isBlank()) {
            return false;
        }
        return e.equals(a) || e.contains(a) || a.contains(e);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            case BLANK, _NONE, ERROR -> "";
        };
    }

    private byte[] createXlsx(String content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("结果");
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
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private OutputFileDto toDto(OutputRecord record) {
        return new OutputFileDto(record.id(), record.name(), record.extension(), record.createdAt());
    }

    public record TemplateTaskResult(OutputFileDto output, Map<String, String> mappedFields, List<String> trace) {
    }
}
