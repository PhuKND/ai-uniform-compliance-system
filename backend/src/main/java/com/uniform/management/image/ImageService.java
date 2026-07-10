package com.uniform.management.image;

import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.ImageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Service
public class ImageService {

    private static final int PRE_AI_MAX_BYTES = 1024 * 1024;
    private static final int PRE_AI_MIN_BYTES = 768 * 1024;

    private final EvaluationImageRepository imageRepository;

    public ImageService(EvaluationImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    @Transactional
    public EvaluationImage saveUpload(MultipartFile file, ImageType imageType) {
        try {
            return saveBytes(
                    cleanFileName(file.getOriginalFilename()),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    file.getBytes(),
                    imageType
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Không thể lưu ảnh tải lên", ex);
        }
    }

    @Transactional
    public EvaluationImage savePreAiUpload(MultipartFile file, ImageType imageType) {
        try {
            byte[] data = file.getBytes();
            if (data.length <= PRE_AI_MAX_BYTES) {
                return saveBytes(
                        cleanFileName(file.getOriginalFilename()),
                        file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                        data,
                        imageType
                );
            }
            byte[] compressed = compressImage(data);
            return saveBytes(
                    jpgName(cleanFileName(file.getOriginalFilename())),
                    "image/jpeg",
                    compressed,
                    imageType
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Kh\u00f4ng th\u1ec3 l\u01b0u \u1ea3nh t\u1ea3i l\u00ean", ex);
        }
    }

    @Transactional
    public EvaluationImage saveBytes(String fileName, String contentType, byte[] data, ImageType imageType) {
        EvaluationImage image = new EvaluationImage();
        image.setFileName(cleanFileName(fileName));
        image.setContentType(contentType == null || contentType.isBlank() ? "image/jpeg" : contentType);
        image.setData(data);
        image.setFileSize(data.length);
        image.setImageType(imageType);
        return imageRepository.save(image);
    }

    @Transactional(readOnly = true)
    public EvaluationImage get(Long id) {
        return imageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ảnh: " + id));
    }

    private String cleanFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "image.jpg";
        }
        return fileName.replace("\\", "_").replace("/", "_");
    }

    private String jpgName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem + ".jpg";
    }

    private byte[] compressImage(byte[] original) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        if (source == null) {
            return original.length <= PRE_AI_MAX_BYTES ? original : original;
        }

        BufferedImage rgb = toRgb(source);
        byte[] bestUnder = null;
        int bestSize = 0;
        double scale = 1.0;
        float[] qualities = {0.95f, 0.92f, 0.90f, 0.88f, 0.85f, 0.82f, 0.80f, 0.78f, 0.75f, 0.72f, 0.70f, 0.68f, 0.65f, 0.62f, 0.60f};

        while (scale >= 0.24) {
            BufferedImage scaled = scaleImage(rgb, scale);
            for (float quality : qualities) {
                byte[] encoded = encodeJpeg(scaled, quality);
                int size = encoded.length;
                if (size <= PRE_AI_MAX_BYTES && size > bestSize) {
                    bestUnder = encoded;
                    bestSize = size;
                    if (size >= PRE_AI_MIN_BYTES) {
                        return encoded;
                    }
                }
            }
            scale *= 0.90;
        }

        return bestUnder == null ? original : bestUnder;
    }

    private BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private BufferedImage scaleImage(BufferedImage source, double scale) {
        if (scale >= 0.999) {
            return source;
        }
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer is available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
