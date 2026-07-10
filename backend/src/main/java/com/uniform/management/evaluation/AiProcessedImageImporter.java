package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.image.ImageService;
import com.uniform.management.uniformai.UniformAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiProcessedImageImporter {

    private static final Logger log = LoggerFactory.getLogger(AiProcessedImageImporter.class);

    private final ImageService imageService;
    private final UniformAiClient uniformAiClient;
    private final EvaluationResultExtractor extractor;
    private final Path configuredOutputRoot;
    private final Set<String> allowedExtensions;

    public AiProcessedImageImporter(
            ImageService imageService,
            UniformAiClient uniformAiClient,
            EvaluationResultExtractor extractor,
            @Value("${uniform.ai.output-root:}") String outputRoot,
            @Value("${uniform.ai.allowed-image-extensions:jpg,jpeg,png}") String allowedExtensions
    ) {
        this.imageService = imageService;
        this.uniformAiClient = uniformAiClient;
        this.extractor = extractor;
        this.configuredOutputRoot = outputRoot == null || outputRoot.isBlank()
                ? null
                : Path.of(outputRoot.trim()).toAbsolutePath().normalize();
        this.allowedExtensions = parseAllowedExtensions(allowedExtensions);
    }

    public EvaluationImage importProcessedImage(Long runId, EvaluationMethod method, JsonNode candidate) {
        String sourcePath = extractor.processedImagePath(candidate);
        String sourceUrl = extractor.processedImageUrl(candidate);
        String methodKey = method.getCandidateKey();
        log.info(
                "event=ai_processed_image_received runId={} method={} outputPath={} outputUrl={}",
                runId, methodKey, sourcePath, sourceUrl
        );

        LoadedImage loaded = null;
        List<String> failures = new ArrayList<>();
        if (sourcePath != null && !sourcePath.isBlank()) {
            try {
                loaded = loadApprovedLocalFile(runId, methodKey, sourcePath);
            } catch (Exception ex) {
                failures.add("local path: " + safeReason(ex));
                log.warn(
                        "event=ai_processed_image_path_rejected runId={} method={} outputPath={} reason={}",
                        runId, methodKey, sourcePath, safeReason(ex)
                );
            }
        } else {
            failures.add("AI response did not contain an output path");
            log.warn("event=ai_processed_image_path_missing runId={} method={}", runId, methodKey);
        }

        if (loaded == null && sourceUrl != null && !sourceUrl.isBlank()) {
            try {
                byte[] bytes = uniformAiClient.downloadImage(sourceUrl);
                String fileName = fileNameFromUrl(sourceUrl, fallbackFileName(method));
                loaded = validateImage(fileName, bytes, "secured AI HTTP bridge");
                log.info(
                        "event=ai_processed_image_bridge_validated runId={} method={} sourceSize={} width={} height={} contentType={}",
                        runId, methodKey, bytes.length, loaded.width(), loaded.height(), loaded.contentType()
                );
            } catch (Exception ex) {
                failures.add("AI HTTP bridge: " + safeReason(ex));
                log.warn(
                        "event=ai_processed_image_bridge_failed runId={} method={} reason={}",
                        runId, methodKey, safeReason(ex)
                );
            }
        }

        if (loaded == null) {
            String reason = failures.isEmpty() ? "AI response did not identify an output image" : String.join("; ", failures);
            log.error("event=ai_processed_image_import_failed runId={} method={} reason={}", runId, methodKey, reason);
            throw new ProcessedImageImportException("Processed image import failed: " + reason);
        }

        EvaluationImage image = imageService.saveBytes(
                loaded.fileName(),
                loaded.contentType(),
                loaded.bytes(),
                method.isMethod1Slot()
                        ? com.uniform.management.common.enums.ImageType.METHOD_1_PROCESSED
                        : com.uniform.management.common.enums.ImageType.METHOD_2_PROCESSED
        );
        log.info(
                "event=ai_processed_image_imported runId={} method={} sourceSize={} width={} height={} imageId={} imageUrl={}",
                runId, methodKey, loaded.bytes().length, loaded.width(), loaded.height(), image.getId(), imageUrl(image)
        );
        return image;
    }

    private LoadedImage loadApprovedLocalFile(Long runId, String methodKey, String rawPath) throws IOException {
        if (configuredOutputRoot == null) {
            throw new IOException("UNIFORM_AI_OUTPUT_ROOT is not configured");
        }
        if (!Files.exists(configuredOutputRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("configured output root does not exist");
        }
        Path approvedRoot = configuredOutputRoot.toRealPath();
        Path received = Path.of(rawPath.trim());
        Path candidate = received.isAbsolute() ? received : approvedRoot.resolve(received);
        candidate = candidate.normalize();

        boolean exists = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
        long sourceSize = exists && Files.isRegularFile(candidate) ? Files.size(candidate) : 0L;
        log.info(
                "event=ai_processed_image_path_check runId={} method={} validationRoot={} normalizedPath={} sourceExists={} sourceSize={}",
                runId, methodKey, approvedRoot, candidate, exists, sourceSize
        );
        if (!exists) {
            throw new IOException("source file does not exist");
        }

        Path realFile = candidate.toRealPath();
        if (!realFile.startsWith(approvedRoot)) {
            throw new IOException("source file is outside the approved output root");
        }
        if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("source is not a regular file");
        }
        requireAllowedExtension(realFile.getFileName().toString());

        byte[] bytes = Files.readAllBytes(realFile);
        LoadedImage loaded = validateImage(realFile.getFileName().toString(), bytes, "approved local output");
        log.info(
                "event=ai_processed_image_path_validated runId={} method={} normalizedPath={} sourceExists=true sourceSize={} width={} height={} contentType={}",
                runId, methodKey, realFile, bytes.length, loaded.width(), loaded.height(), loaded.contentType()
        );
        return loaded;
    }

    private LoadedImage validateImage(String fileName, byte[] bytes, String source) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException(source + " returned an empty file");
        }
        requireAllowedExtension(fileName);

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException(source + " is not a readable image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException(source + " is not a supported image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String normalizedFormat = "jpg".equals(format) ? "jpeg" : format;
                if (!allowedExtensions.contains(normalizedFormat)
                        && !("jpeg".equals(normalizedFormat) && allowedExtensions.contains("jpg"))) {
                    throw new IOException("detected image format is not allowed: " + normalizedFormat);
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException(source + " has invalid dimensions");
                }
                return new LoadedImage(
                        fileName,
                        contentType(normalizedFormat),
                        bytes,
                        width,
                        height
                );
            } finally {
                reader.dispose();
            }
        }
    }

    private void requireAllowedExtension(String fileName) throws IOException {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!allowedExtensions.contains(extension)) {
            throw new IOException("file extension is not allowed");
        }
    }

    private Set<String> parseAllowedExtensions(String configured) {
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim)
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .forEach(values::add);
        if (values.isEmpty()) {
            values.addAll(Set.of("jpg", "jpeg", "png"));
        }
        return Set.copyOf(values);
    }

    private String contentType(String format) {
        return switch (format) {
            case "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> "image/" + format;
        };
    }

    private String fileNameFromUrl(String imageUrl, String fallback) {
        try {
            String path = URI.create(imageUrl).getPath();
            if (path != null && !path.isBlank()) {
                String name = Path.of(path).getFileName().toString();
                if (!name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // The secured client performs the authoritative URL validation.
        }
        return fallback;
    }

    private String fallbackFileName(EvaluationMethod method) {
        return method.isMethod1Slot()
                ? "method-1-processed.jpg"
                : "method-2-processed.jpg";
    }

    private String imageUrl(EvaluationImage image) {
        return image.getId() == null ? null : "/api/images/" + image.getId();
    }

    private String safeReason(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record LoadedImage(String fileName, String contentType, byte[] bytes, int width, int height) {
    }

    public static class ProcessedImageImportException extends RuntimeException {
        public ProcessedImageImportException(String message) {
            super(message);
        }
    }
}
