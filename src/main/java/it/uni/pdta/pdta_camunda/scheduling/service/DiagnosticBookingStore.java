package it.uni.pdta.pdta_camunda.scheduling.service;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticBookingDecisionRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticRequestContext;
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

    @Transactional(readOnly = true)
    public Optional<DiagnosticBookingDecision> find(String taskKey) {
        return decisions.findById(taskKey);
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
