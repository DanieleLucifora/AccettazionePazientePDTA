package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Risorsa diagnostica fisica (es. TAC Sala 1, PET Ala B).
 * Traccia i tipi di esame supportati e lo stato operativo corrente.
 */
@Entity
@Table(name = "diagnostic_resource")
@Getter
@Setter
public class DiagnosticResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Codice breve univoco (es. "TAC-01", "PET-B2"). */
    @Column(name = "code", unique = true, nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** Tipi di esame che questa risorsa è in grado di eseguire. */
    @ElementCollection(targetClass = ExamType.class, fetch = FetchType.EAGER)
    @CollectionTable(
        name = "resource_supported_exam_type",
        joinColumns = @JoinColumn(name = "resource_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", length = 32)
    private Set<ExamType> supportedExamTypes = new HashSet<>();

    /** Identificativo del sito/reparto (es. "RADIOLOGIA_PIANO_2"). */
    @Column(name = "site_id", length = 64)
    private String siteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ResourceStatus status = ResourceStatus.AVAILABLE;

    /** Minuti totali di operatività al giorno (es. 8h = 480). */
    @Column(name = "daily_capacity_minutes")
    private Integer dailyCapacityMinutes;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
