package it.uni.pdta.pdta_camunda.scheduling.domain.entity;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Log immutabile di ogni evento rilevante su una risorsa diagnostica.
 * Usato per analisi KPI, audit e future previsioni di carico.
 */
@Entity
@Table(name = "resource_event")
@Getter
@Setter
public class ResourceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** Slot coinvolto nell'evento (nullable per eventi di stato risorsa). */
    @Column(name = "booking_slot_id")
    private Long bookingSlotId;

    /** Protocollo coinvolto (nullable, valorizzato per PROTOCOL_UPDATED). */
    @Column(name = "exam_protocol_id")
    private Long examProtocolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private ResourceEventType eventType;

    @Column(name = "event_ts", nullable = false)
    private LocalDateTime eventTs;

    /** Attore che ha generato l'evento (es. nome utente o ruolo Camunda). */
    @Column(name = "actor", length = 64)
    private String actor;

    /** Payload JSON opzionale per dati extra (durata effettiva, note, ecc.). */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    /** ID di correlazione con il process instance Camunda. */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @PrePersist
    void prePersist() {
        if (eventTs == null) {
            eventTs = LocalDateTime.now();
        }
    }
}
