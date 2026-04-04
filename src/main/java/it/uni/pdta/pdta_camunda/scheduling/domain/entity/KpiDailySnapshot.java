package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Snapshot giornaliero KPI calcolato a fine giornata sugli eventi risorsa.
 */
@Entity
@Table(name = "kpi_daily_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_kpi_daily_snapshot_date", columnNames = "snapshot_date"))
@Getter
@Setter
public class KpiDailySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "requests_created", nullable = false)
    private Integer requestsCreated = 0;

    @Column(name = "slots_assigned", nullable = false)
    private Integer slotsAssigned = 0;

    @Column(name = "slots_rescheduled", nullable = false)
    private Integer slotsRescheduled = 0;

    @Column(name = "exams_completed", nullable = false)
    private Integer examsCompleted = 0;

    @Column(name = "exams_canceled", nullable = false)
    private Integer examsCanceled = 0;

    @Column(name = "distinct_resources", nullable = false)
    private Integer distinctResources = 0;

    @Column(name = "distinct_cases", nullable = false)
    private Integer distinctCases = 0;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    @PreUpdate
    void prePersistOrUpdate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
