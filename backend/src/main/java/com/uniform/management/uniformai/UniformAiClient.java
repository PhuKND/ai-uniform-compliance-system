package com.uniform.management.uniformai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;

@Service
public class UniformAiClient {

    private final WebClient uniformClient;
    private final WebClient faceClient;
    private final String uniformBaseUrl;
    private final String faceBaseUrl;
    private final String publicMediaBaseUrl;
    private final String yolov8V2Endpoint;
    private final String groundingDinoV2Endpoint;
    private final String lightweightEndpoint;
    private final String realtimeEndpoint;
    private final String realtimePoseModelPath;
    private final String realtimeUniformModelPath;
    private final String realtimeInsightFaceConfig;
    private final String realtimeFaceEmbeddingSource;
    private final double realtimeConfidenceThreshold;
    private final double realtimeFaceThreshold;
    private final int realtimeFrameSize;
    private final Duration timeout;
    private final Duration advancedTimeout;
    private final Duration realtimeTimeout;

    public UniformAiClient(
            WebClient.Builder builder,
            @Value("${uniform.ai.base-url}") String uniformBaseUrl,
            @Value("${uniform.ai.face-base-url}") String faceBaseUrl,
            @Value("${uniform.ai.public-base-url:}") String publicMediaBaseUrl,
            @Value("${uniform.ai.timeout-seconds}") long timeoutSeconds,
            @Value("${uniform.ai.advanced-evaluation-timeout-seconds:300}") long advancedTimeoutSeconds,
            @Value("${uniform.ai.yolov8-v2-endpoint:/api/uniform/evaluate/yolov8-v2}") String yolov8V2Endpoint,
            @Value("${uniform.ai.grounding-dino-v2-endpoint:/api/uniform/evaluate/grounding-dino-v2}") String groundingDinoV2Endpoint,
            @Value("${uniform.ai.lightweight-endpoint:/api/ai/evaluate-student-lightweight}") String lightweightEndpoint,
            @Value("${uniform.ai.realtime-endpoint:/api/realtime-camera/analyze-frame}") String realtimeEndpoint,
            @Value("${uniform.ai.realtime-timeout-seconds:10}") long realtimeTimeoutSeconds,
            @Value("${uniform.ai.realtime.yolov8-pose-model:}") String realtimePoseModelPath,
            @Value("${uniform.ai.realtime.yolov8-uniform-model:}") String realtimeUniformModelPath,
            @Value("${uniform.ai.realtime.insightface-config:}") String realtimeInsightFaceConfig,
            @Value("${uniform.ai.realtime.face-embedding-source:registered-face-data}") String realtimeFaceEmbeddingSource,
            @Value("${uniform.ai.realtime.confidence-threshold:0.35}") double realtimeConfidenceThreshold,
            @Value("${uniform.ai.realtime.face-threshold:0.45}") double realtimeFaceThreshold,
            @Value("${uniform.ai.realtime.frame-size:640}") int realtimeFrameSize
    ) {
        this.uniformBaseUrl = trimTrailingSlash(uniformBaseUrl);
        this.faceBaseUrl = trimTrailingSlash(faceBaseUrl);
        this.publicMediaBaseUrl = optionalBaseUrl(publicMediaBaseUrl);
        this.yolov8V2Endpoint = normalizeEndpoint(yolov8V2Endpoint);
        this.groundingDinoV2Endpoint = normalizeEndpoint(groundingDinoV2Endpoint);
        this.lightweightEndpoint = normalizeEndpoint(lightweightEndpoint);
        this.realtimeEndpoint = normalizeEndpoint(realtimeEndpoint);
        this.realtimePoseModelPath = realtimePoseModelPath == null ? "" : realtimePoseModelPath.trim();
        this.realtimeUniformModelPath = realtimeUniformModelPath == null ? "" : realtimeUniformModelPath.trim();
        this.realtimeInsightFaceConfig = realtimeInsightFaceConfig == null ? "" : realtimeInsightFaceConfig.trim();
        this.realtimeFaceEmbeddingSource = realtimeFaceEmbeddingSource == null ? "" : realtimeFaceEmbeddingSource.trim();
        this.realtimeConfidenceThreshold = realtimeConfidenceThreshold;
        this.realtimeFaceThreshold = realtimeFaceThreshold;
        this.realtimeFrameSize = realtimeFrameSize;
        this.uniformClient = builder.clone().baseUrl(this.uniformBaseUrl).build();
        this.faceClient = builder.clone().baseUrl(this.faceBaseUrl).build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.advancedTimeout = Duration.ofSeconds(advancedTimeoutSeconds);
        this.realtimeTimeout = Duration.ofSeconds(realtimeTimeoutSeconds);
    }

    public JsonNode health() {
        return uniformClient.get()
                .uri("/api/ai/health")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);
    }

    public JsonNode evaluateStudent(MultipartFile image, String studentCode, String faceMode, String uniformMethod) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        if (studentCode != null && !studentCode.isBlank()) {
            body.part("student_id", studentCode);
        }
        body.part("face_mode", faceMode == null ? "identify" : faceMode);
        body.part("run_face", "true");
        body.part("run_uniform", "true");
        body.part("uniform_method", uniformMethod);
        body.part("save_annotated", "true");

        return uniformClient.post()
                .uri("/api/ai/evaluate-student")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(advancedTimeout);
    }

    public JsonNode evaluateStudentCandidates(MultipartFile image, String studentCode, String faceMode) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        if (studentCode != null && !studentCode.isBlank()) {
            body.part("student_id", studentCode);
        }
        body.part("face_mode", faceMode == null ? "identify" : faceMode);
        body.part("run_face", "true");
        body.part("run_uniform", "true");
        body.part("save_annotated", "true");

        return uniformClient.post()
                .uri("/api/ai/evaluate-student")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(advancedTimeout);
    }

    public JsonNode evaluateUniformCandidates(MultipartFile image) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        body.part("save_annotated", "true");

        return uniformClient.post()
                .uri("/api/uniform/evaluate")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(advancedTimeout);
    }

    public JsonNode evaluateAdvanced(MultipartFile image, String studentCode, String faceMode, String methodKey) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        if (studentCode != null && !studentCode.isBlank()) {
            body.part("student_id", studentCode);
        }
        body.part("face_mode", faceMode == null ? "identify" : faceMode);
        body.part("save_annotated", "true");

        String endpoint = "GROUNDING_DINO_V2".equalsIgnoreCase(methodKey)
                ? groundingDinoV2Endpoint
                : yolov8V2Endpoint;
        return uniformClient.post()
                .uri(endpoint)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(advancedTimeout);
    }

    public JsonNode evaluateLightweight(MultipartFile image, String studentCode, String faceMode, String methodKey) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        if (studentCode != null && !studentCode.isBlank()) {
            body.part("student_id", studentCode);
        }
        body.part("face_mode", faceMode == null ? "identify" : faceMode);
        if (methodKey != null && !methodKey.isBlank()) {
            body.part("uniform_method", methodKey);
            body.part("selected_method", methodKey);
        }
        body.part("save_annotated", "true");
        body.part("use_schp", "false");
        body.part("use_florence", "false");

        return uniformClient.post()
                .uri(lightweightEndpoint)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(advancedTimeout);
    }

    public JsonNode analyzeRealtimeFrame(MultipartFile image, Integer frameWidth, Integer frameHeight) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        if (frameWidth != null) {
            body.part("frame_width", frameWidth.toString());
        }
        if (frameHeight != null) {
            body.part("frame_height", frameHeight.toString());
        }
        body.part("pipeline", "yolov8_pose_insightface_yolov8_uniform");
        body.part("run_pose", "true");
        body.part("run_face", "true");
        body.part("run_uniform", "true");
        body.part("save_annotated", "false");
        body.part("use_grounding_dino", "false");
        body.part("use_schp", "false");
        body.part("use_florence", "false");
        body.part("confidence_threshold", Double.toString(realtimeConfidenceThreshold));
        body.part("face_threshold", Double.toString(realtimeFaceThreshold));
        body.part("frame_size", Integer.toString(realtimeFrameSize));
        addOptionalPart(body, "yolov8_pose_model", realtimePoseModelPath);
        addOptionalPart(body, "yolov8_uniform_model", realtimeUniformModelPath);
        addOptionalPart(body, "insightface_config", realtimeInsightFaceConfig);
        addOptionalPart(body, "face_embedding_source", realtimeFaceEmbeddingSource);

        return uniformClient.post()
                .uri(realtimeEndpoint)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(realtimeTimeout);
    }

    public JsonNode enrollFace(String studentCode, String studentName, MultipartFile image, String sampleLabel) {
        MultipartBodyBuilder body = faceBody(studentCode, image, sampleLabel);
        body.part("student_name", studentName);
        return postFaceMultipart("/api/face/enroll", body);
    }

    public JsonNode enrollFaceSample(String studentCode, MultipartFile image, String sampleLabel) {
        MultipartBodyBuilder body = faceBody(studentCode, image, sampleLabel);
        return postFaceMultipart("/api/face/enroll-sample", body);
    }

    public JsonNode deleteFaceData(String studentCode) {
        return faceClient.delete()
                .uri("/api/face/students/{studentCode}", studentCode)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);
    }

    public JsonNode renameFaceData(String oldStudentCode, String newStudentCode, String studentName) {
        return faceClient.patch()
                .uri("/api/face/students/{studentCode}/rename", oldStudentCode)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("new_student_id", newStudentCode, "student_name", studentName))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);
    }

    public byte[] downloadImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new UniformAiException("AI response did not include a processed image URL");
        }
        String resolved = resolveServiceImageUrl(imageUrl);
        if (resolved == null) {
            throw new UniformAiException("AI response image URL is outside the configured AI service origins");
        }
        return uniformClient
                .get()
                .uri(URI.create(resolved))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(timeout);
    }

    public String resolveImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String normalized = imageUrl.trim().replace("\\", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        URI absoluteUri = parseHttpUri(normalized);
        if (absoluteUri != null) {
            if (publicMediaBaseUrl != null && shouldRewriteToPublicBase(absoluteUri)) {
                return publicMediaBaseUrl + uriPathAndQuery(absoluteUri);
            }
            if (isLocalHost(absoluteUri.getHost())) {
                return null;
            }
            return normalized;
        }
        String browserBaseUrl = publicMediaBaseUrl != null
                ? publicMediaBaseUrl
                : isLocalBaseUrl(uniformBaseUrl) ? null : uniformBaseUrl;
        if (browserBaseUrl == null) {
            return null;
        }
        return browserBaseUrl + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private String resolveServiceImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String normalized = imageUrl.trim().replace("\\", "/");
        if (normalized.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        URI absoluteUri = parseHttpUri(normalized);
        if (absoluteUri != null) {
            return isApprovedAiMediaOrigin(absoluteUri) ? normalized : null;
        }
        return uniformBaseUrl + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private boolean isApprovedAiMediaOrigin(URI uri) {
        URI serviceUri = parseHttpUri(uniformBaseUrl);
        if (serviceUri != null && sameHostAndPort(uri, serviceUri)) {
            return true;
        }
        URI publicUri = parseHttpUri(publicMediaBaseUrl);
        return publicUri != null && sameHostAndPort(uri, publicUri);
    }

    private URI parseHttpUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return uri;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private boolean shouldRewriteToPublicBase(URI uri) {
        if (isLocalHost(uri.getHost())) {
            return true;
        }
        URI serviceUri = parseHttpUri(uniformBaseUrl);
        if (serviceUri == null) {
            return false;
        }
        return sameHostAndPort(uri, serviceUri);
    }

    private boolean sameHostAndPort(URI first, URI second) {
        String firstHost = first.getHost();
        String secondHost = second.getHost();
        if (firstHost == null || secondHost == null || !firstHost.equalsIgnoreCase(secondHost)) {
            return false;
        }
        return effectivePort(first) == effectivePort(second);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String uriPathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private boolean isLocalBaseUrl(String value) {
        URI uri = parseHttpUri(value);
        return uri != null && isLocalHost(uri.getHost());
    }

    private boolean isLocalHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase();
        return normalized.equals("localhost")
                || normalized.equals("127.0.0.1")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::1")
                || normalized.startsWith("127.");
    }

    private String optionalBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return trimTrailingSlash(url.trim());
    }

    private JsonNode postFaceMultipart(String uri, MultipartBodyBuilder body) {
        return faceClient.post()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);
    }

    private MultipartBodyBuilder faceBody(String studentCode, MultipartFile image, String sampleLabel) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", multipartResource(image))
                .filename(cleanFileName(image))
                .contentType(mediaType(image));
        body.part("student_id", studentCode);
        if (sampleLabel != null && !sampleLabel.isBlank()) {
            body.part("sample_label", sampleLabel);
        }
        return body;
    }

    private void addOptionalPart(MultipartBodyBuilder body, String name, String value) {
        if (value != null && !value.isBlank()) {
            body.part(name, value);
        }
    }

    private ByteArrayResource multipartResource(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return cleanFileName(file);
                }
            };
        } catch (IOException ex) {
            throw new UniformAiException("Không thể đọc ảnh tải lên", ex);
        }
    }

    private MediaType mediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return MediaType.parseMediaType(contentType);
    }

    private String cleanFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "upload.jpg" : name.replace("\\", "_").replace("/", "_");
    }

    private String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/api/realtime-camera/analyze-frame";
        }
        String trimmed = endpoint.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
