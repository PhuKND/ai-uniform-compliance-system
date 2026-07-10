package com.uniform.management.facedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.facedata.dto.FaceDataStatusResponse;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformai.UniformAiClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.List;

@Service
public class FaceDataService {

    private final StudentRepository studentRepository;
    private final FaceDataRecordRepository faceDataRecordRepository;
    private final UniformAiClient uniformAiClient;
    private final ObjectMapper objectMapper;

    public FaceDataService(
            StudentRepository studentRepository,
            FaceDataRecordRepository faceDataRecordRepository,
            UniformAiClient uniformAiClient,
            ObjectMapper objectMapper
    ) {
        this.studentRepository = studentRepository;
        this.faceDataRecordRepository = faceDataRecordRepository;
        this.uniformAiClient = uniformAiClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FaceDataStatusResponse enroll(String studentCode, MultipartFile image, String sampleLabel, boolean additionalSample) {
        Student student = getStudent(studentCode);
        JsonNode response = additionalSample
                ? uniformAiClient.enrollFaceSample(student.getFaceDataId(), image, sampleLabel)
                : uniformAiClient.enrollFace(student.getFaceDataId(), student.getFullName(), image, sampleLabel);
        FaceDataRecord record = saveRecord(student, true, response);
        return FaceDataStatusResponse.of(student, record);
    }

    @Transactional
    public FaceDataStatusResponse reEnroll(String studentCode, MultipartFile image, String sampleLabel) {
        Student student = getStudent(studentCode);
        try {
            uniformAiClient.deleteFaceData(student.getFaceDataId());
        } catch (WebClientResponseException.NotFound ignored) {
            // Re-enroll still works when the AI service has no prior face folder.
        }
        JsonNode response = uniformAiClient.enrollFace(student.getFaceDataId(), student.getFullName(), image, sampleLabel);
        FaceDataRecord record = saveRecord(student, true, response);
        return FaceDataStatusResponse.of(student, record);
    }

    @Transactional
    public FaceDataStatusResponse delete(String studentCode) {
        Student student = getStudent(studentCode);
        JsonNode response = uniformAiClient.deleteFaceData(student.getFaceDataId());
        FaceDataRecord record = saveRecord(student, false, response);
        return FaceDataStatusResponse.of(student, record);
    }

    @Transactional(readOnly = true)
    public FaceDataStatusResponse status(String studentCode) {
        Student student = getStudent(studentCode);
        FaceDataRecord record = faceDataRecordRepository.findTopByStudentOrderByCreatedAtDesc(student).orElse(null);
        return FaceDataStatusResponse.of(student, record);
    }

    @Transactional(readOnly = true)
    public List<FaceDataStatusResponse> allStatuses() {
        return studentRepository.findAll().stream()
                .map(student -> FaceDataStatusResponse.of(
                        student,
                        faceDataRecordRepository.findTopByStudentOrderByCreatedAtDesc(student).orElse(null)
                ))
                .toList();
    }

    private FaceDataRecord saveRecord(Student student, boolean enrolled, JsonNode response) {
        FaceDataRecord record = new FaceDataRecord();
        record.setStudent(student);
        record.setStudentCodeSnapshot(student.getStudentCode());
        record.setFaceDataId(student.getFaceDataId());
        record.setEnrolled(enrolled);
        record.setSampleCount(extractSampleCount(response));
        record.setLastSyncedAt(Instant.now());
        try {
            record.setAiResponseJson(objectMapper.writeValueAsString(response));
        } catch (Exception ex) {
            record.setAiResponseJson(String.valueOf(response));
        }
        return faceDataRecordRepository.save(record);
    }

    private int extractSampleCount(JsonNode response) {
        JsonNode count = response.path("data").path("student").path("sample_count");
        if (count.isNumber()) {
            return count.asInt();
        }
        JsonNode deleted = response.path("data").path("sample_count_deleted");
        if (deleted.isNumber()) {
            return 0;
        }
        return 1;
    }

    private Student getStudent(String studentCode) {
        return studentRepository.findByStudentCode(studentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy học sinh: " + studentCode));
    }
}
