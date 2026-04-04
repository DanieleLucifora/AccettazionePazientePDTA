package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Slot di prenotazione che collega una risorsa diagnostica a un esame.
 * Anti-overlap garantito via query JPQL + lock pessimistico sulla risorsa.
 */
@Entity
@Table(name = "booking_slot")
@Getter
@Setter
@ToString(exclude = {"resource", "examProtocol"})
public class BookingSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private DiagnosticResource resource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_protocol_id", nullable = false)
    private ExamProtocol examProtocol;

    /** Chiave del process instance Camunda (per correlazione). */
    @Column(name = "process_instance_key")
    private Long processInstanceKey;

    @Column(name = "patient_id", length = 64)
    private String patientId;

    /** Ruolo Camunda che ha creato lo slot (ONCOLOGI, CHIRURGIA, …). */
    @Column(name = "created_by_role", length = 64)
    private String createdByRole;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SlotStatus status = SlotStatus.BOOKED;

    @Column(name = "cancel_reason", length = 256)
    private String cancelReason;

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
