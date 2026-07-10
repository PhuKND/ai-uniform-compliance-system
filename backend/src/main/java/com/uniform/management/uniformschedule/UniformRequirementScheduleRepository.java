package com.uniform.management.uniformschedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

public interface UniformRequirementScheduleRepository extends JpaRepository<UniformRequirementSchedule, Long> {

    List<UniformRequirementSchedule> findByClassNameOrderByDayOfWeekAsc(String className);

    Optional<UniformRequirementSchedule> findByClassNameAndDayOfWeek(String className, DayOfWeek dayOfWeek);
}
