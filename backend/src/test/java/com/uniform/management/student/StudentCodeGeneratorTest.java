package com.uniform.management.student;

import com.uniform.management.common.enums.MoralityLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(StudentCodeGenerator.class)
@ActiveProfiles("test")
class StudentCodeGeneratorTest {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentCodeGenerator generator;

    @Test
    void generatesVietnameseLastNameCodesAndIncrements() {
        Student first = new Student();
        first.setStudentCode("PHU001");
        first.setFaceDataId("PHU001");
        first.setFullName("Nguyễn Phú");
        first.setMoralityLevel(MoralityLevel.GOOD);
        studentRepository.save(first);

        assertThat(generator.generate("Trần Phước Phú")).isEqualTo("PHU002");
        assertThat(generator.generate("Nguyễn Văn An")).isEqualTo("AN001");
    }

    @Test
    void normalizesVietnameseSpecialD() {
        assertThat(generator.prefixFromFullName("Đỗ Văn Đức")).isEqualTo("DUC");
    }
}
