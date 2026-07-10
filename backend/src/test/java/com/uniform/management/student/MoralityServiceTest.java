package com.uniform.management.student;

import com.uniform.management.common.enums.MoralityLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MoralityServiceTest {

    private final MoralityService moralityService = new MoralityService(null);

    @Test
    void calculatesVietnameseConductBands() {
        assertThat(moralityService.calculateLevel(100)).isEqualTo(MoralityLevel.GOOD);
        assertThat(moralityService.calculateLevel(80)).isEqualTo(MoralityLevel.GOOD);
        assertThat(moralityService.calculateLevel(79)).isEqualTo(MoralityLevel.FAIR);
        assertThat(moralityService.calculateLevel(65)).isEqualTo(MoralityLevel.FAIR);
        assertThat(moralityService.calculateLevel(64)).isEqualTo(MoralityLevel.AVERAGE);
        assertThat(moralityService.calculateLevel(50)).isEqualTo(MoralityLevel.AVERAGE);
        assertThat(moralityService.calculateLevel(49)).isEqualTo(MoralityLevel.WEAK);
    }
}
