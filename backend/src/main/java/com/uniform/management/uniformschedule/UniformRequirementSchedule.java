package com.uniform.management.uniformschedule;

import com.uniform.management.common.model.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "uniform_requirement_schedules",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_uniform_schedule_class_day",
                columnNames = {"class_name", "day_of_week"}
        )
)
public class UniformRequirementSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_name", nullable = false, length = 64)
    private String className;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 16)
    private DayOfWeek dayOfWeek;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "uniform_requirement_schedule_components",
            joinColumns = @JoinColumn(name = "schedule_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "component", nullable = false, length = 48)
    private Set<UniformComponent> requiredComponents = new LinkedHashSet<>();

    public Long getId() {
        return id;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public Set<UniformComponent> getRequiredComponents() {
        return requiredComponents;
    }

    public void setRequiredComponents(Set<UniformComponent> requiredComponents) {
        this.requiredComponents = requiredComponents == null ? new LinkedHashSet<>() : requiredComponents;
    }
}
