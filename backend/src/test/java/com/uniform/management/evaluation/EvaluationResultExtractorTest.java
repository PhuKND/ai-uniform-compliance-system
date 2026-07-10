package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.enums.EvaluationMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationResultExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvaluationResultExtractor extractor = new EvaluationResultExtractor();

    @Test
    void extractsCandidatesFromIntegratedAiResponse() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "success": true,
                  "data": {
                    "face": {"identity": "AN001"},
                    "uniform": {
                      "evaluation_id": "uniform_1",
                      "pre_ai_image": "storage/uniform/pre_ai/test.jpg",
                      "pre_ai_image_url": "/api/uniform/pre-ai/test.jpg",
                      "candidates": [
                        {
                          "method": "grounding_dino_schp_florence2",
                          "processed_image": "outputs/yolov8/dino.jpg",
                          "processed_image_url": "/api/uniform/yolov8/outputs/dino.jpg",
                          "result": {"final_summary": {"is_compliant": true, "score": 90}}
                        },
                        {
                          "method": "yolov8_schp_florence2",
                          "processed_image": "outputs/yolov8/yolo.jpg",
                          "processed_image_url": "/api/uniform/yolov8/outputs/yolo.jpg",
                          "result": {"final_summary": {"is_compliant": false, "score": 60}}
                        }
                      ]
                    }
                  }
                }
                """);

        JsonNode yolo = extractor.candidate(response, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);

        assertThat(extractor.recognizedStudentCode(response)).isEqualTo("AN001");
        assertThat(extractor.uniformAiEvaluationId(response)).isEqualTo("uniform_1");
        assertThat(extractor.preAiImageUrl(response)).isEqualTo("/api/uniform/pre-ai/test.jpg");
        assertThat(extractor.processedImagePath(yolo)).isEqualTo("outputs/yolov8/yolo.jpg");
        assertThat(extractor.processedImageUrl(yolo)).isEqualTo("/api/uniform/yolov8/outputs/yolo.jpg");
        assertThat(extractor.candidateComplianceStatus(yolo)).isEqualTo(com.uniform.management.common.enums.ComplianceStatus.NON_COMPLIANT);
    }

    @Test
    void selectsFinalAnnotatedPathFromEachCandidateIndependently() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "candidates": [
                    {
                      "method": "grounding_dino_schp_florence2",
                      "result": {
                        "final_annotated_image_path": "outputs/dino-final.jpg",
                        "final_annotated_image_url": "/outputs/dino-final.jpg"
                      }
                    },
                    {
                      "method": "yolov8_schp_florence2",
                      "processed_image_path": "outputs/yolo-final.jpg",
                      "final_annotated_image_url": "/outputs/yolo-final.jpg"
                    }
                  ]
                }
                """);

        JsonNode method1 = extractor.candidate(response, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        JsonNode method2 = extractor.candidate(response, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);

        assertThat(extractor.processedImagePath(method1)).isEqualTo("outputs/dino-final.jpg");
        assertThat(extractor.processedImagePath(method2)).isEqualTo("outputs/yolo-final.jpg");
        assertThat(extractor.processedImageUrl(method1)).isEqualTo("/outputs/dino-final.jpg");
        assertThat(extractor.processedImageUrl(method2)).isEqualTo("/outputs/yolo-final.jpg");
    }

    @Test
    void extractsCandidatesWithV2MethodKeys() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "candidates": [
                    {"method": "GROUNDING_DINO_V2", "processed_image": "outputs/dino-v2.jpg"},
                    {"method": "YOLOV8_V2", "processed_image": "outputs/yolo-v2.jpg"}
                  ]
                }
                """);

        JsonNode method1 = extractor.candidate(response, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        JsonNode method2 = extractor.candidate(response, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);

        assertThat(extractor.processedImagePath(method1)).isEqualTo("outputs/dino-v2.jpg");
        assertThat(extractor.processedImagePath(method2)).isEqualTo("outputs/yolo-v2.jpg");
    }
}
