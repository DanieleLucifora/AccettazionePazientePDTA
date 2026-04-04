package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Protocollo clinico per un esame diagnostico.
 * La combinazione (examType, bodyPart, effectiveFrom) è univoca:
 * ogni nuova versione del protocollo viene inserita con una nuova effectiveFrom.
 */
@Entity
@Table(
    name = "exam_protocol",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_protocol_type_part_date",
        columnNames = {"exam_type", "body_part", "effective_from"}
    )
)
@Getter
@Setter
public class ExamProtocol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false, length = 32)
    private ExamType examType;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_part", nullable = false, length = 32)
    private BodyPart bodyPart;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "setup_minutes", nullable = false)
    private int setupMinutes;

    @Column(name = "exam_minutes", nullable = false)
    private int examMinutes;

    @Column(name = "teardown_minutes", nullable = false)
    private int teardownMinutes;

    /** Calcolato automaticamente: setup + exam + teardown. */
    @Column(name = "total_slot_minutes", nullable = false)
    private int totalSlotMinutes;

    @Column(name = "requires_contrast", nullable = false)
    private boolean requiresContrast;

    @Column(name = "requires_fasting", nullable = false)
    private boolean requiresFasting;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Data da cui questo protocollo è in vigore. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "notes", length = 512)
    private String notes;

    /** Chi ha aggiornato il protocollo (es. "tecnico_lab:mario.rossi"). */
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

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
        recalcTotal();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
        recalcTotal();
    }

    private void recalcTotal() {
        totalSlotMinutes = setupMinutes + examMinutes + teardownMinutes;
    }
}
