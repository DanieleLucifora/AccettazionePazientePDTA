package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand.Action;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "diagnostic_booking_decision")
@Getter
@Setter
public class DiagnosticBookingDecision {
    @Id
    @Column(length = 19)
    private String taskKey;
    private Long processInstanceKey;
    @Column(nullable = false, length = 32)
    private String requesterRole;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action;
    @Column(nullable = false, length = 2000)
    private String reason;
    @ManyToOne(fetch = FetchType.EAGER)
    private BookingSlot slot;
    @Column(nullable = false)
    private LocalDateTime decidedAt;
    @Version
    private Long version;
}
