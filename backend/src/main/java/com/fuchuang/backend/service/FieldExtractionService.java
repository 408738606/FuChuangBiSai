package com.fuchuang.backend.service;

import com.fuchuang.backend.model.DocumentRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FieldExtractionService {
    private static final Pattern KV_PATTERN = Pattern.compile("^\\s*([\\p{L}\\p{N}（）()·\\-_/%]+)\\s*[:：]\\s*(.+)$");
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2})");
    private static final Pattern RANGE_PATTERN = Pattern.compile("(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}).{0,16}(\\d{4}[/-]\\d{1,2}[/-]\\d{1,2})");
    private static final Pattern CITY_PATTERN = Pattern.compile("([\\p{L}]{2,}(市|省|区|县))");

    private final KnowledgeBaseService knowledgeBaseService;

    public FieldExtractionService(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    public ExtractionResult extractCandidates(List<DocumentRecord> sources, String instruction) {
        DateRange dateRange = parseDateRange(instruction).orElse(null);
        String cityFilter = parseCity(instruction).orElse("");

        List<Candidate> candidates = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        trace.add("载入源文档: " + sources.size());

        for (DocumentRecord source : sources) {
            String text = knowledgeBaseService.previewText(source.id());
            String[] lines = text.split("\\R");
            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!linePassesFilters(line, dateRange, cityFilter)) {
                    continue;
                }

                Matcher kv = KV_PATTERN.matcher(line);
                if (kv.find()) {
                    candidates.add(new Candidate(kv.group(1).trim(), kv.group(2).trim(), line, source.name()));
                }

                String[] tabSplit = line.split("\\t");
                if (tabSplit.length >= 2) {
                    for (int i = 0; i + 1 < tabSplit.length; i += 2) {
                        String key = tabSplit[i].trim();
                        String value = tabSplit[i + 1].trim();
                        if (!key.isBlank() && !value.isBlank()) {
                            candidates.add(new Candidate(key, value, line, source.name()));
                        }
                    }
                }
            }
        }

        trace.add("抽取候选字段: " + candidates.size());
        if (dateRange != null) {
            trace.add("启用日期过滤: " + dateRange.start + " ~ " + dateRange.end);
        }
        if (!cityFilter.isBlank()) {
            trace.add("启用城市过滤: " + cityFilter);
        }

        return new ExtractionResult(candidates, trace);
    }

    public Optional<Candidate> matchField(String targetField, List<Candidate> candidates, String instruction) {
        String normalizedField = normalize(targetField);
        if (normalizedField.isBlank()) {
            return Optional.empty();
        }

        return candidates.stream()
                .max(Comparator.comparingInt(c -> score(normalizedField, c, instruction)));
    }

    public Map<String, String> toDedupMap(List<Candidate> candidates) {
        Map<String, String> dedup = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            dedup.putIfAbsent(c.key(), c.value());
        }
        return dedup;
    }

    private int score(String normalizedField, Candidate candidate, String instruction) {
        int score = 0;
        String key = normalize(candidate.key());
        String context = normalize(candidate.context());
        if (key.equals(normalizedField)) {
            score += 120;
        }
        if (key.contains(normalizedField) || normalizedField.contains(key)) {
            score += 80;
        }
        if (context.contains(normalizedField)) {
            score += 40;
        }
        String normalizedInstruction = normalize(instruction == null ? "" : instruction);
        if (!normalizedInstruction.isBlank() && context.contains(normalizedInstruction)) {
            score += 20;
        }
        if (candidate.value().matches(".*\\d.*")) {
            score += 5;
        }
        return score;
    }

    private boolean linePassesFilters(String line, DateRange dateRange, String cityFilter) {
        if (!cityFilter.isBlank() && !line.contains(cityFilter)) {
            return false;
        }
        if (dateRange == null) {
            return true;
        }
        Matcher matcher = DATE_PATTERN.matcher(line);
        boolean foundDate = false;
        while (matcher.find()) {
            foundDate = true;
            Optional<LocalDate> parsed = parseDate(matcher.group(1));
            if (parsed.isPresent() && dateRange.includes(parsed.get())) {
                return true;
            }
        }
        return !foundDate;
    }

    private Optional<DateRange> parseDateRange(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = RANGE_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return Optional.empty();
        }
        Optional<LocalDate> start = parseDate(matcher.group(1));
        Optional<LocalDate> end = parseDate(matcher.group(2));
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DateRange(start.get(), end.get()));
    }

    private Optional<String> parseCity(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = CITY_PATTERN.matcher(instruction);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<LocalDate> parseDate(String token) {
        String normalized = token.replace('/', '-');
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-M-d", Locale.ROOT);
        try {
            return Optional.of(LocalDate.parse(normalized, formatter));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record DateRange(LocalDate start, LocalDate end) {
        boolean includes(LocalDate date) {
            return (date.isEqual(start) || date.isAfter(start)) && (date.isEqual(end) || date.isBefore(end));
        }
    }

    public record Candidate(String key, String value, String context, String sourceName) {
    }

    public record ExtractionResult(List<Candidate> candidates, List<String> trace) {
    }
}
