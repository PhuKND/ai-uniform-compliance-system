package com.uniform.management.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdvancedEvaluationControllerTest {

    @Mock
    private EvaluationService evaluationService;

    private AdvancedEvaluationController controller;
    private MockMultipartFile image;

    @BeforeEach
    void setUp() {
        controller = new AdvancedEvaluationController(evaluationService);
        image = new MockMultipartFile("image", "student.jpg", "image/jpeg", new byte[]{1});
    }

    @Test
    void selectedMethodTakesPrecedenceWhenBothCompatibilityFieldsArePresent() {
        controller.lightweight(image, "AN001", "YOLOV8_V2", "GROUNDING_DINO_V2");

        verify(evaluationService).lightweight(image, "AN001", "YOLOV8_V2");
    }

    @Test
    void uniformMethodRemainsSupportedAsCompatibilityAlias() {
        controller.lightweight(image, "AN001", " ", "GROUNDING_DINO_V2");

        verify(evaluationService).lightweight(image, "AN001", "GROUNDING_DINO_V2");
    }
}
