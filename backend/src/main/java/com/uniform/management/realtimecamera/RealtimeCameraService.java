package com.uniform.management.realtimecamera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.realtimecamera.dto.RealtimeCameraAnalysisResponse;
import com.uniform.management.realtimecamera.dto.RealtimeFaceResponse;
import com.uniform.management.realtimecamera.dto.RealtimeIdentityResponse;
import com.uniform.management.realtimecamera.dto.RealtimeKeypointResponse;
import com.uniform.management.realtimecamera.dto.RealtimePersonResponse;
import com.uniform.management.realtimecamera.dto.RealtimePoseLinkResponse;
import com.uniform.management.realtimecamera.dto.RealtimeUniformDetectionResponse;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformai.UniformAiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RealtimeCameraService {

    private static final String PIPELINE_NAME = "YOLOv8 Pose + InsightFace + YOLOv8 Uniform";
    private static final List<String> COCO_KEYPOINT_NAMES = List.of(
            "nose",
            "left_eye",
            "right_eye",
            "left_ear",
            "right_ear",
            "left_shoulder",
            "right_shoulder",
            "left_elbow",
            "right_elbow",
            "left_wrist",
            "right_wrist",
            "left_hip",
            "right_hip",
            "left_knee",
            "right_knee",
            "left_ankle",
            "right_ankle"
    );
    private static final List<RealtimePoseLinkResponse> COCO_SKELETON = List.of(
            new RealtimePoseLinkResponse("left_shoulder", "right_shoulder"),
            new RealtimePoseLinkResponse("left_shoulder", "left_elbow"),
            new RealtimePoseLinkResponse("left_elbow", "left_wrist"),
            new RealtimePoseLinkResponse("right_shoulder", "right_elbow"),
            new RealtimePoseLinkResponse("right_elbow", "right_wrist"),
            new RealtimePoseLinkResponse("left_shoulder", "left_hip"),
            new RealtimePoseLinkResponse("right_shoulder", "right_hip"),
            new RealtimePoseLinkResponse("left_hip", "right_hip"),
            new RealtimePoseLinkResponse("left_hip", "left_knee"),
            new RealtimePoseLinkResponse("left_knee", "left_ankle"),
            new RealtimePoseLinkResponse("right_hip", "right_knee"),
            new RealtimePoseLinkResponse("right_knee", "right_ankle"),
            new RealtimePoseLinkResponse("nose", "left_eye"),
            new RealtimePoseLinkResponse("nose", "right_eye"),
            new RealtimePoseLinkResponse("left_eye", "left_ear"),
            new RealtimePoseLinkResponse("right_eye", "right_ear")
    );

    private final UniformAiClient uniformAiClient;
    private final StudentRepository studentRepository;
    private final double faceThreshold;

    public RealtimeCameraService(
            UniformAiClient uniformAiClient,
            StudentRepository studentRepository,
            @Value("${uniform.ai.realtime.face-threshold:0.45}") double faceThreshold
    ) {
        this.uniformAiClient = uniformAiClient;
        this.studentRepository = studentRepository;
        this.faceThreshold = faceThreshold;
    }

    @Transactional(readOnly = true)
    public RealtimeCameraAnalysisResponse analyzeFrame(MultipartFile image, Integer frameWidth, Integer frameHeight) {
        if (image == null || image.isEmpty()) {
            throw new BadRequestException("Camera frame is required");
        }

        long started = System.nanoTime();
        JsonNode aiResponse = uniformAiClient.analyzeRealtimeFrame(image, frameWidth, frameHeight);
        long elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        return normalize(aiResponse, elapsedMs, frameWidth, frameHeight);
    }

    private RealtimeCameraAnalysisResponse normalize(
            JsonNode root,
            long elapsedMs,
            Integer requestFrameWidth,
            Integer requestFrameHeight
    ) {
        JsonNode data = payload(root);
        boolean success = root.path("success").asBoolean(data.path("success").asBoolean(true));
        String message = firstText(
                root.path("message"),
                data.path("message"),
                data.path("status_message")
        );
        if (message == null) {
            message = success ? "OK" : "Real-time AI service unavailable";
        }

        Integer frameWidth = firstInteger(
                data.path("frameWidth"),
                data.path("frame_width"),
                data.path("imageWidth"),
                data.path("image_width")
        );
        Integer frameHeight = firstInteger(
                data.path("frameHeight"),
                data.path("frame_height"),
                data.path("imageHeight"),
                data.path("image_height")
        );
        Long processingTime = firstLong(
                data.path("processingTimeMs"),
                data.path("processing_time_ms"),
                data.path("latencyMs"),
                data.path("latency_ms"),
                root.path("processingTimeMs"),
                root.path("processing_time_ms")
        );

        JsonNode selectedPersonNode = selectedPersonNode(data);
        JsonNode faceNode = firstObject(
                data.path("face"),
                data.path("selectedFace"),
                data.path("selected_face"),
                data.path("faceMatch"),
                data.path("face_match"),
                selectedPersonNode.path("face")
        );

        RealtimePersonResponse selectedPerson = toPerson(selectedPersonNode);
        RealtimeFaceResponse face = toFace(faceNode);
        RealtimeIdentityResponse identity = toIdentity(data, faceNode);

        return new RealtimeCameraAnalysisResponse(
                success,
                message,
                frameWidth == null ? requestFrameWidth : frameWidth,
                frameHeight == null ? requestFrameHeight : frameHeight,
                processingTime == null ? elapsedMs : processingTime,
                selectedPerson,
                identity,
                face,
                uniformDetections(data),
                PIPELINE_NAME
        );
    }

    private JsonNode payload(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return MissingNode.getInstance();
        }
        JsonNode data = root.path("data");
        return data.isObject() ? data : root;
    }

    private JsonNode selectedPersonNode(JsonNode data) {
        JsonNode explicitSelection = firstObject(
                data.path("selectedPerson"),
                data.path("selected_person"),
                data.path("selectedTarget"),
                data.path("selected_target"),
                data.path("closestPerson"),
                data.path("closest_person"),
                data.path("person"),
                data.path("pose").path("selectedPerson"),
                data.path("pose").path("selected_person"),
                data.path("pose").path("closest_person"),
                data.path("pose").path("person")
        );
        if (explicitSelection.isObject()) {
            return explicitSelection;
        }

        return bestPersonFromArrays(
                data.path("persons"),
                data.path("pose").path("persons"),
                data.path("people"),
                data.path("pose").path("people"),
                data.path("detections").path("persons")
        );
    }

    private JsonNode bestPersonFromArrays(JsonNode... arrays) {
        JsonNode best = MissingNode.getInstance();
        double bestScore = 0;
        for (JsonNode array : arrays) {
            if (!array.isArray()) {
                continue;
            }
            for (JsonNode candidate : array) {
                double score = personSelectionScore(candidate);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private double personSelectionScore(JsonNode person) {
        if (!person.isObject()) {
            return 0;
        }
        double area = personArea(person);
        if (area <= 0) {
            return 0;
        }
        Double confidence = firstDouble(
                person.path("confidence"),
                person.path("score"),
                person.path("personConfidence"),
                person.path("person_confidence")
        );
        if (confidence == null || confidence <= 0) {
            confidence = 0.5;
        }
        List<RealtimeKeypointResponse> keypoints = keypoints(person);
        long validKeypoints = keypoints.stream()
                .filter(keypoint -> keypoint.confidence() == null || keypoint.confidence() >= 0.2)
                .count();
        double keypointFactor = keypoints.isEmpty() ? 0.6 : Math.min(1.0, validKeypoints / 8.0);
        return area * Math.min(confidence, 1.0) * (0.35 + (keypointFactor * 0.65));
    }

    private double personArea(JsonNode person) {
        List<Double> box = bbox(person);
        if (box.size() == 4) {
            double width = Math.max(0, box.get(2) - box.get(0));
            double height = Math.max(0, box.get(3) - box.get(1));
            return width * height;
        }

        List<RealtimeKeypointResponse> keypoints = keypoints(person).stream()
                .filter(keypoint -> keypoint.confidence() == null || keypoint.confidence() >= 0.2)
                .toList();
        if (keypoints.size() < 2) {
            return 0;
        }

        double minX = keypoints.stream().mapToDouble(RealtimeKeypointResponse::x).min().orElse(0);
        double maxX = keypoints.stream().mapToDouble(RealtimeKeypointResponse::x).max().orElse(0);
        double minY = keypoints.stream().mapToDouble(RealtimeKeypointResponse::y).min().orElse(0);
        double maxY = keypoints.stream().mapToDouble(RealtimeKeypointResponse::y).max().orElse(0);
        return Math.max(0, maxX - minX) * Math.max(0, maxY - minY);
    }

    private RealtimePersonResponse toPerson(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        List<RealtimeKeypointResponse> keypoints = keypoints(node);
        return new RealtimePersonResponse(
                bbox(node),
                firstDouble(node.path("confidence"), node.path("score"), node.path("personConfidence"), node.path("person_confidence")),
                keypoints,
                keypoints.isEmpty() ? List.of() : COCO_SKELETON
        );
    }

    private RealtimeFaceResponse toFace(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new RealtimeFaceResponse(
                bbox(node),
                firstDouble(node.path("confidence"), node.path("score"), node.path("similarity"), node.path("match_confidence"))
        );
    }

    private RealtimeIdentityResponse toIdentity(JsonNode data, JsonNode faceNode) {
        Double confidence = firstDouble(
                data.path("identity").path("confidence"),
                data.path("identity").path("score"),
                data.path("identity").path("similarity"),
                faceNode.path("confidence"),
                faceNode.path("score"),
                faceNode.path("similarity"),
                faceNode.path("match_confidence"),
                data.path("face_confidence")
        );
        Optional<Student> student = resolveStudent(data, faceNode);
        boolean strongEnough = confidence == null || confidence >= faceThreshold || confidence > 1.0;
        if (student.isPresent() && strongEnough) {
            Student matched = student.get();
            return new RealtimeIdentityResponse(
                    true,
                    matched.getId(),
                    matched.getStudentCode(),
                    matched.getFullName(),
                    matched.getClassName(),
                    confidence,
                    matched.getFullName()
            );
        }
        return RealtimeIdentityResponse.unknown(confidence);
    }

    private Optional<Student> resolveStudent(JsonNode data, JsonNode faceNode) {
        Set<String> candidates = new LinkedHashSet<>();
        collectIdentityCandidates(candidates, data);
        collectIdentityCandidates(candidates, data.path("identity"));
        collectIdentityCandidates(candidates, data.path("student"));
        collectIdentityCandidates(candidates, faceNode);
        collectIdentityCandidates(candidates, faceNode.path("identity"));
        collectIdentityCandidates(candidates, faceNode.path("student"));
        collectIdentityCandidates(candidates, faceNode.path("best_match"));
        collectIdentityCandidates(candidates, faceNode.path("bestMatch"));
        collectIdentityCandidates(candidates, faceNode.path("best_match").path("student"));
        collectIdentityCandidates(candidates, data.path("best_match"));
        collectIdentityCandidates(candidates, data.path("bestMatch"));

        for (String candidate : candidates) {
            if (isUnknownIdentity(candidate)) {
                continue;
            }
            Optional<Student> byStudentCode = studentRepository.findByStudentCode(candidate);
            if (byStudentCode.isPresent()) {
                return byStudentCode;
            }
            Optional<Student> byFaceDataId = studentRepository.findByFaceDataId(candidate);
            if (byFaceDataId.isPresent()) {
                return byFaceDataId;
            }
            Long numericId = parseLong(candidate);
            if (numericId != null) {
                Optional<Student> byId = studentRepository.findById(numericId);
                if (byId.isPresent()) {
                    return byId;
                }
            }
        }
        return Optional.empty();
    }

    private void collectIdentityCandidates(Set<String> candidates, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        addCandidate(candidates, node);
        addCandidate(candidates, node.path("studentCode"));
        addCandidate(candidates, node.path("student_code"));
        addCandidate(candidates, node.path("studentId"));
        addCandidate(candidates, node.path("student_id"));
        addCandidate(candidates, node.path("faceDataId"));
        addCandidate(candidates, node.path("face_data_id"));
        addCandidate(candidates, node.path("identity"));
        addCandidate(candidates, node.path("id"));
        addCandidate(candidates, node.path("code"));
        addCandidate(candidates, node.path("label"));
    }

    private void addCandidate(Set<String> candidates, JsonNode node) {
        String text = text(node);
        if (text != null) {
            candidates.add(text);
        }
    }

    private List<RealtimeUniformDetectionResponse> uniformDetections(JsonNode data) {
        JsonNode detections = firstArray(
                data.path("uniformDetections"),
                data.path("uniform_detections"),
                data.path("uniform").path("detections"),
                data.path("uniform").path("objects"),
                data.path("yolov8Uniform").path("detections"),
                data.path("yolov8_uniform").path("detections"),
                data.path("detections").path("uniform"),
                data.path("detections")
        );
        if (!detections.isArray()) {
            return List.of();
        }

        List<RealtimeUniformDetectionResponse> result = new ArrayList<>();
        for (JsonNode detection : detections) {
            String className = firstText(
                    detection.path("className"),
                    detection.path("class_name"),
                    detection.path("label"),
                    detection.path("name"),
                    detection.path("category")
            );
            if (className == null && detection.path("cls").isNumber()) {
                className = "class_" + detection.path("cls").asInt();
            }
            if (className == null || "person".equalsIgnoreCase(className)) {
                continue;
            }
            result.add(new RealtimeUniformDetectionResponse(
                    className,
                    firstDouble(detection.path("confidence"), detection.path("score"), detection.path("conf")),
                    bbox(detection)
            ));
        }
        return result;
    }

    private List<RealtimeKeypointResponse> keypoints(JsonNode person) {
        JsonNode source = firstArray(
                person.path("keypoints"),
                person.path("poseKeypoints"),
                person.path("pose_keypoints"),
                person.path("pose").path("keypoints")
        );
        if (source.isArray()) {
            return arrayKeypoints(source);
        }
        JsonNode objectSource = firstObject(person.path("keypoints"), person.path("pose").path("keypoints"));
        if (objectSource.isObject()) {
            List<RealtimeKeypointResponse> result = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = objectSource.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                RealtimeKeypointResponse keypoint = keypointFromNode(field.getValue(), field.getKey(), result.size());
                if (keypoint != null) {
                    result.add(keypoint);
                }
            }
            return result;
        }
        return List.of();
    }

    private List<RealtimeKeypointResponse> arrayKeypoints(JsonNode source) {
        List<RealtimeKeypointResponse> result = new ArrayList<>();
        if (source.size() > 0 && source.get(0).isNumber() && source.size() % 3 == 0) {
            for (int index = 0; index + 2 < source.size(); index += 3) {
                String name = keypointName(index / 3);
                result.add(new RealtimeKeypointResponse(
                        name,
                        source.get(index).asDouble(),
                        source.get(index + 1).asDouble(),
                        source.get(index + 2).asDouble()
                ));
            }
            return result;
        }

        for (int index = 0; index < source.size(); index += 1) {
            RealtimeKeypointResponse keypoint = keypointFromNode(source.get(index), null, index);
            if (keypoint != null) {
                result.add(keypoint);
            }
        }
        return result;
    }

    private RealtimeKeypointResponse keypointFromNode(JsonNode node, String fallbackName, int index) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String name = firstText(node.path("name"), node.path("label"), node.path("keypoint"));
        if (name == null) {
            name = fallbackName == null ? keypointName(index) : fallbackName;
        }
        if (node.isArray() && node.size() >= 2) {
            return new RealtimeKeypointResponse(
                    name,
                    node.get(0).asDouble(),
                    node.get(1).asDouble(),
                    node.size() >= 3 && node.get(2).isNumber() ? node.get(2).asDouble() : null
            );
        }
        Double x = firstDouble(node.path("x"), node.path("px"));
        Double y = firstDouble(node.path("y"), node.path("py"));
        if (x == null || y == null) {
            return null;
        }
        return new RealtimeKeypointResponse(
                name,
                x,
                y,
                firstDouble(node.path("confidence"), node.path("score"), node.path("conf"))
        );
    }

    private String keypointName(int index) {
        return index >= 0 && index < COCO_KEYPOINT_NAMES.size() ? COCO_KEYPOINT_NAMES.get(index) : "keypoint_" + index;
    }

    private List<Double> bbox(JsonNode node) {
        JsonNode array = firstArray(
                node.path("bbox"),
                node.path("box"),
                node.path("boundingBox"),
                node.path("bounding_box"),
                node.path("xyxy")
        );
        if (array.isArray() && array.size() >= 4) {
            return List.of(array.get(0).asDouble(), array.get(1).asDouble(), array.get(2).asDouble(), array.get(3).asDouble());
        }

        Double x1 = firstDouble(node.path("x1"), node.path("left"), node.path("xmin"), node.path("x_min"));
        Double y1 = firstDouble(node.path("y1"), node.path("top"), node.path("ymin"), node.path("y_min"));
        Double x2 = firstDouble(node.path("x2"), node.path("right"), node.path("xmax"), node.path("x_max"));
        Double y2 = firstDouble(node.path("y2"), node.path("bottom"), node.path("ymax"), node.path("y_max"));
        Double width = firstDouble(node.path("width"), node.path("w"));
        Double height = firstDouble(node.path("height"), node.path("h"));
        if (x1 != null && y1 != null && x2 == null && width != null) {
            x2 = x1 + width;
        }
        if (x1 != null && y1 != null && y2 == null && height != null) {
            y2 = y1 + height;
        }
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return List.of(x1, y1, x2, y2);
        }
        return List.of();
    }

    private JsonNode firstObject(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isObject()) {
                return node;
            }
            if (node.isArray()) {
                JsonNode first = firstArrayItem(node);
                if (first.isObject()) {
                    return first;
                }
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return MissingNode.getInstance();
    }

    private JsonNode firstArrayItem(JsonNode node) {
        return node != null && node.isArray() && node.size() > 0 ? node.get(0) : MissingNode.getInstance();
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = text(node);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Double firstDouble(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isNumber()) {
                return node.asDouble();
            }
            String text = text(node);
            if (text != null) {
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException ignored) {
                    // Try the next candidate.
                }
            }
        }
        return null;
    }

    private Integer firstInteger(JsonNode... nodes) {
        Double value = firstDouble(nodes);
        return value == null ? null : value.intValue();
    }

    private Long firstLong(JsonNode... nodes) {
        Double value = firstDouble(nodes);
        return value == null ? null : value.longValue();
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isUnknownIdentity(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.equals("unknown")
                || normalized.equals("none")
                || normalized.equals("null")
                || normalized.equals("nguoi la");
    }
}
