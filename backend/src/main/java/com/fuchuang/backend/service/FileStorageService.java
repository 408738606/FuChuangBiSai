package com.fuchuang.backend.service;

import com.fuchuang.backend.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {
    private final Path root;
    private final Path uploadDir;
    private final Path outputDir;

    public FileStorageService(AppProperties properties) {
        this.root = Path.of(properties.storageRoot()).toAbsolutePath().normalize();
        this.uploadDir = root.resolve("uploads");
        this.outputDir = root.resolve("outputs");
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(uploadDir);
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path saveUpload(MultipartFile file) {
        String name = sanitize(file.getOriginalFilename());
        Path target = safeResolve(uploadDir, UUID.randomUUID() + "_" + name);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path saveGenerated(String fileName, byte[] bytes) {
        Path target = safeResolve(outputDir, UUID.randomUUID() + "_" + sanitize(fileName));
        try {
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path copyToUploads(Path sourcePath, String preferredName) {
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Invalid source path.");
        }
        Path target = safeResolve(uploadDir, UUID.randomUUID() + "_" + sanitize(preferredName));
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Resource asResource(String absolutePath) {
        return new FileSystemResource(Path.of(absolutePath));
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "file.dat";
        }
        String sanitized = input.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (sanitized.contains("..")) {
            throw new IllegalArgumentException("Invalid file name.");
        }
        return sanitized.isBlank() ? "file.dat" : sanitized;
    }

    private Path safeResolve(Path baseDir, String fileName) {
        Path resolved = baseDir.resolve(fileName).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Illegal file path.");
        }
        return resolved;
    }
}
