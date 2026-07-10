package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.ImageType;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.image.ImageService;
import com.uniform.management.uniformai.UniformAiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProcessedImageImporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationResultExtractor extractor = new EvaluationResultExtractor();

    @TempDir
    Path tempDir;

    @Test
    void importsLargeJpegFromApprovedOutputRootWithoutHttpDownload() throws Exception {
        Path approvedRoot = Files.createDirectories(tempDir.resolve("outputs"));
        byte[] jpeg = largeJpeg();
        Path output = approvedRoot.resolve("method-1-final.jpg");
        Files.write(output, jpeg);

        ImageService imageService = mock(ImageService.class);
        UniformAiClient aiClient = mock(UniformAiClient.class);
        EvaluationImage saved = new EvaluationImage();
        when(imageService.saveBytes(anyString(), anyString(), any(byte[].class), any(ImageType.class))).thenReturn(saved);
        AiProcessedImageImporter importer = new AiProcessedImageImporter(
                imageService, aiClient, extractor, approvedRoot.toString(), "jpg,jpeg,png"
        );
        ObjectNode candidate = objectMapper.createObjectNode().put("processed_image", output.toString());

        EvaluationImage imported = importer.importProcessedImage(
                35L, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE, candidate
        );

        assertThat(imported).isSameAs(saved);
        assertThat(jpeg.length).isGreaterThan(256 * 1024);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(imageService).saveBytes(
                org.mockito.ArgumentMatchers.eq("method-1-final.jpg"),
                org.mockito.ArgumentMatchers.eq("image/jpeg"),
                bytes.capture(),
                org.mockito.ArgumentMatchers.eq(ImageType.METHOD_1_PROCESSED)
        );
        assertThat(bytes.getValue()).isEqualTo(jpeg);
        verify(aiClient, never()).downloadImage(anyString());
    }

    @Test
    void rejectsLocalPathOutsideApprovedOutputRoot() throws Exception {
        Path approvedRoot = Files.createDirectories(tempDir.resolve("outputs"));
        Path outside = tempDir.resolve("outside.jpg");
        Files.write(outside, largeJpeg());
        ImageService imageService = mock(ImageService.class);
        UniformAiClient aiClient = mock(UniformAiClient.class);
        AiProcessedImageImporter importer = new AiProcessedImageImporter(
                imageService, aiClient, extractor, approvedRoot.toString(), "jpg,jpeg,png"
        );
        ObjectNode candidate = objectMapper.createObjectNode().put("processed_image", outside.toString());

        assertThatThrownBy(() -> importer.importProcessedImage(
                8L, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE, candidate
        ))
                .isInstanceOf(AiProcessedImageImporter.ProcessedImageImportException.class)
                .hasMessageContaining("outside the approved output root");
        verify(imageService, never()).saveBytes(anyString(), anyString(), any(byte[].class), any(ImageType.class));
    }

    @Test
    void importsLargeJpegThroughSecuredBridgeWhenFilesystemIsNotShared() throws Exception {
        byte[] jpeg = largeJpeg();
        ImageService imageService = mock(ImageService.class);
        UniformAiClient aiClient = mock(UniformAiClient.class);
        EvaluationImage saved = new EvaluationImage();
        when(aiClient.downloadImage("/api/uniform/yolov8/outputs/final.jpg")).thenReturn(jpeg);
        when(imageService.saveBytes(anyString(), anyString(), any(byte[].class), any(ImageType.class))).thenReturn(saved);
        AiProcessedImageImporter importer = new AiProcessedImageImporter(
                imageService, aiClient, extractor, "", "jpg,jpeg,png"
        );
        ObjectNode candidate = objectMapper.createObjectNode()
                .put("processed_image_url", "/api/uniform/yolov8/outputs/final.jpg");

        EvaluationImage imported = importer.importProcessedImage(
                9L, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE, candidate
        );

        assertThat(imported).isSameAs(saved);
        verify(imageService).saveBytes(
                org.mockito.ArgumentMatchers.eq("final.jpg"),
                org.mockito.ArgumentMatchers.eq("image/jpeg"),
                org.mockito.ArgumentMatchers.argThat(data -> data.length > 256 * 1024),
                org.mockito.ArgumentMatchers.eq(ImageType.METHOD_2_PROCESSED)
        );
    }

    private byte[] largeJpeg() throws Exception {
        BufferedImage image = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(35L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", output);
            return output.toByteArray();
        }
    }
}
