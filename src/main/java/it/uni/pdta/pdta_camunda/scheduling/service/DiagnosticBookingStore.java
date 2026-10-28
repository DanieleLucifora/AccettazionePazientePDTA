package it.uni.pdta.pdta_camunda.scheduling.service;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticBookingDecisionRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticRequestContext;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticReportCommand;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.BookingSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DiagnosticBookingStore {
    private final DiagnosticBookingDecisionRepository decisions;
    private final BookingService booking;
    private final ResourceEventService events;
    private final BookingSlotRepository slots;

    @Transactional(readOnly = true)
    public Optional<DiagnosticBookingDecision> find(String taskKey) {
        return decisions.findById(taskKey);
    }

    @Transactional(readOnly = true)
    public java.util.List<DiagnosticBookingDecision> forProcess(String key) {
        return decisions.findByProcessInstanceKeyOrderByDecidedAtDesc(Long.valueOf(key));
    }

    @Transactional(readOnly = true)
    public java.util.List<HistoricalSlot> historicalSlots(String key) {
        var linked = decisions.findByProcessInstanceKeyOrderByDecidedAtDesc(Long.valueOf(key)).stream()
                .filter(item -> item.getSlot() != null).map(item -> item.getSlot().getId()).collect(java.util.stream.Collectors.toSet());
        return slots.findByProcessInstanceKey(Long.valueOf(key)).stream().filter(slot -> !linked.contains(slot.getId()))
                .sorted(java.util.Comparator.comparing(it.uni.pdta.pdta_camunda.scheduling.domain.entity.BookingSlot::getStartTime))
                .map(slot -> new HistoricalSlot(slot.getId().toString(), slot.getCreatedByRole(),
                        new SlotSuggestion(slot.getResource(), slot.getExamProtocol(), slot.getStartTime(), slot.getEndTime()),
                        slot.getStatus())).toList();
    }

    public record HistoricalSlot(String id, String role, SlotSuggestion slot, SlotStatus status) {}

    @Transactional
    public DiagnosticBookingDecision saveReport(String taskKey, String processKey, DiagnosticRequestContext request,
            DiagnosticReportCommand command, LocalDateTime now) {
        if (!command.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referto obbligatorio, massimo 10000 caratteri.");
        var decision = decisions.findLockedByTaskKey(taskKey)
                .orElseThrow(() -> conflict("Prenota l'esame prima di refertare."));
        if (!decision.getProcessInstanceKey().toString().equals(processKey)
                || !decision.getRequesterRole().equals(request.requesterRole())
                || decision.getAction() == DiagnosticBookingCommand.Action.REJECT || decision.getSlot() == null) {
            throw conflict("Prenotazione non coerente con la richiesta.");
        }
        var slot = slots.findByIdWithLock(decision.getSlot().getId()).orElseThrow();
        if (decision.getReportText() != null) {
            if (!decision.getReportText().equals(command.text())) {
                throw conflict("Referto gia' salvato: non e' possibile sostituirlo. Verifica l'invio.");
            }
            if (slot.getStatus() != SlotStatus.COMPLETED) throw conflict("Stato della prenotazione non coerente con il referto.");
            return decision;
        }
        if (slot.getStatus() != SlotStatus.BOOKED && slot.getStatus() != SlotStatus.IN_PROGRESS) {
            throw conflict("Prenotazione non attiva: impossibile refertare.");
        }
        if (slot.getEndTime().isAfter(now)) throw conflict("Refertazione consentita solo dopo la fine della fascia prenotata.");
        if (slot.getExamProtocol().getExamType() != request.examType()
                || slot.getExamProtocol().getBodyPart() != request.bodyPart()) {
            throw conflict("Esame prenotato diverso da quello richiesto.");
        }
        decision.setReportText(command.text());
        decision.setReportedAt(now);
        slot.setStatus(SlotStatus.COMPLETED);
        events.logExamCompleted(slot, "LAB001", processKey, command.text(),
                Map.of("requestedByRole", request.requesterRole(), "requestedById", request.requesterId(),
                        "requestedByName", request.requesterName(), "diagnosticTaskKey", taskKey,
                        "reportedById", "LAB001", "reportedByName", "Mario Neri", "reportedByRole", "Tecnico Laboratorio"));
        return decisions.saveAndFlush(decision);
    }

    @Transactional
    public DiagnosticBookingDecision markReportDelivered(String taskKey) {
        var decision = decisions.findLockedByTaskKey(taskKey).orElseThrow();
        if (decision.getReportText() == null) throw conflict("Nessun referto salvato.");
        if (decision.getReportDeliveredAt() == null) decision.setReportDeliveredAt(LocalDateTime.now());
        return decisions.saveAndFlush(decision);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    @Transactional
    public DiagnosticBookingDecision save(String taskKey, String processKey, String patientId,
            DiagnosticRequestContext request, DiagnosticBookingCommand command, SlotSuggestion selection) {
        if (!command.isValidCommand()
                || (command.action() == DiagnosticBookingCommand.Action.REJECT) != (selection == null)) {
            throw new IllegalArgumentException("Decisione diagnostica e fascia non coerenti.");
        }
        if (decisions.existsById(taskKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Decisione gia' salvata. Aggiorna il dettaglio.");
        }
        var decision = new DiagnosticBookingDecision();
        decision.setTaskKey(taskKey);
        decision.setProcessInstanceKey(Long.valueOf(processKey));
        decision.setRequesterRole(request.requesterRole());
        decision.setAction(command.action());
        decision.setReason(command.reason());
        decision.setDecidedAt(LocalDateTime.now());
        Map<String, Object> metadata = Map.of("requestedByRole", request.requesterRole(),
                "requestedById", request.requesterId(), "requestedByName", request.requesterName(),
                "reportedById", "LAB001", "reportedByName", "Mario Neri", "reportedByRole", "Tecnico Laboratorio",
                "diagnosticTaskKey", taskKey);
        if (selection != null) {
            decision.setSlot(booking.bookSlot(selection.getResourceId(), selection.getProtocolId(),
                    selection.getStartTime(), Long.valueOf(processKey), patientId, request.requesterRole(), metadata));
            if (command.action() == DiagnosticBookingCommand.Action.MODIFY) {
                events.logSlotRescheduled(decision.getSlot(), "LAB001", processKey, command.reason(), metadata);
            }
        }
        // The unique task key and booking share a transaction: concurrent duplicates roll back their slot too.
        return decisions.saveAndFlush(decision);
    }
}
