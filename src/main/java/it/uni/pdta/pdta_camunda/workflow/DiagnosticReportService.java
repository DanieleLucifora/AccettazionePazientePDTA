package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class DiagnosticReportService {
    private final CamundaRestClient rest;
    private final DiagnosticBookingStore store;

    public DiagnosticReportService(CamundaRestClient rest, DiagnosticBookingStore store) {
        this.rest = rest;
        this.store = store;
    }

    public ReportView status(String key) throws Exception {
        var task = task(key);
        var decision = store.find(key).filter(item -> item.getReportText() != null).orElse(null);
        if (decision == null) return null;
        if (decision.getReportDeliveredAt() == null && "COMPLETED".equals(task.state)) {
            var variables = rest.getTypedTaskVariables(key);
            String prefix = decision.getRequesterRole().equals("CHIRURGHI") ? "chirurgo" : "oncologo";
            if (key.equals(DiagnosticRequestContext.text(variables, prefix + "ReportTaskKey"))
                    && decision.getReportText().equals(DiagnosticRequestContext.text(variables, prefix + "ExamResult"))) {
                decision = store.markReportDelivered(key);
            }
        }
        return view(decision);
    }

    public ReportView submit(String key, DiagnosticReportCommand command) throws Exception {
        if (!command.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referto obbligatorio, massimo 10000 caratteri.");
        var task = task(key);
        if (!DiagnosticTaskService.OPERATOR.equals(task.assignee)) throw conflict("Il task non e' assegnato a LAB001.");
        var existing = store.find(key).orElse(null);
        if (existing != null && existing.getReportText() != null && !existing.getReportText().equals(command.text())) {
            throw conflict("Referto gia' salvato: verifica l'invio senza modificarlo.");
        }
        if (existing != null && existing.getReportDeliveredAt() != null) return view(existing);
        if (!"CREATED".equals(task.state)) {
            var status = status(key);
            if (status != null && "SENT".equals(status.status())) return status;
            throw conflict("Task non aperto: invio del referto non confermato. Verifica su Operate.");
        }
        var vars = rest.getTypedTaskVariables(key);
        var request = DiagnosticRequestContext.from(task.elementId, vars);
        claim(key);
        var saved = store.saveReport(key, task.processInstanceKey, request, command, LocalDateTime.now());
        if (saved.getReportDeliveredAt() != null) return view(saved);
        try {
            rest.completeTask(key, variables(key, saved, vars));
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409) {
                throw conflict("Il task e' cambiato. Referto salvato, ma invio non confermato: verifica lo stato.");
            }
            throw exception;
        }
        return view(store.markReportDelivered(key));
    }

    private void claim(String key) {
        try {
            rest.claimTask(key, DiagnosticTaskService.OPERATOR);
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409) {
                throw conflict("Il task e' chiuso o assegnato ad altri. Verifica lo stato.");
            }
            throw exception;
        }
    }

    private CamundaRestClient.UserTask task(String key) throws Exception {
        var task = rest.searchTasks(Map.of("filter", Map.of("userTaskKey", key))).stream()
                .filter(item -> key.equals(item.userTaskKey) && item.candidateGroups.contains("LABORATORIO"))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task diagnostico non trovato."));
        if (!java.util.List.of("Activity_1nqhbxs", "Activity_diagnostic_oncologo").contains(task.elementId)) {
            throw conflict("Attivita' diagnostica non supportata.");
        }
        return task;
    }

    private Map<String, Object> variables(String key, DiagnosticBookingDecision decision, Map<String, Object> vars) {
        var slot = decision.getSlot();
        String role = decision.getRequesterRole();
        String prefix = role.equals("CHIRURGHI") ? "chirurgo" : "oncologo";
        Map<String, Object> result = new HashMap<>();
        result.put("examResult", decision.getReportText());
        result.put(prefix + "ExamResult", decision.getReportText());
        result.put(prefix + "ReportTaskKey", key);
        result.put(prefix + "RequiresExam", false);
        result.put("requesterRole", role);
        result.put("examQueue", java.util.Arrays.stream(DiagnosticRequestContext.text(vars, "examQueue").split(","))
                .map(String::trim).filter(item -> !item.isBlank() && !item.equals(role))
                .collect(java.util.stream.Collectors.joining(",")));
        result.put("bookedSlotId", slot.getId().toString());
        result.put("bookedSlotStart", slot.getStartTime().toString());
        result.put("bookedSlotEnd", slot.getEndTime().toString());
        result.put("bookedResourceCode", slot.getResource().getCode());
        result.put("diagnosticReporterId", DiagnosticTaskService.OPERATOR);
        result.put("diagnosticReporterName", "Mario Neri");
        result.put("diagnosticReporterRole", "Tecnico Laboratorio");
        boolean modified = decision.getAction() == DiagnosticBookingCommand.Action.MODIFY;
        result.put("appointmentModified", modified);
        result.put("examModified", modified);
        result.put("appointmentModificationReason", modified ? decision.getReason() : "");
        result.put("examModificationReason", modified ? decision.getReason() : "");
        result.put("examRejected", false);
        result.put("examRejectionReason", "");
        return result;
    }

    public static ReportView view(DiagnosticBookingDecision decision) {
        return new ReportView(decision.getTaskKey(), decision.getReportText(), decision.getReportedAt(),
                decision.getReportDeliveredAt(), decision.getReportDeliveredAt() == null ? "PENDING" : "SENT");
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record ReportView(String taskKey, String text, LocalDateTime reportedAt, LocalDateTime deliveredAt, String status) {}
}
