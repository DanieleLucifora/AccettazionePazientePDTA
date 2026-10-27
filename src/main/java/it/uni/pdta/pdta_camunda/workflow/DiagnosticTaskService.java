package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiagnosticTaskService {
    public static final String OPERATOR = "LAB001";
    private final CamundaRestClient rest;
    private final DiagnosticExamService exams;
    private final DiagnosticBookingStore store;

    public DiagnosticTaskService(CamundaRestClient rest, DiagnosticExamService exams, DiagnosticBookingStore store) {
        this.rest = rest;
        this.exams = exams;
        this.store = store;
    }

    public List<DiagnosticTask> list() throws Exception {
        List<DiagnosticTask> result = new ArrayList<>();
        for (var task : rest.searchTasks(Map.of("filter", Map.of("state", "CREATED", "candidateGroup", "LABORATORIO")))) {
            if ("CREATED".equals(task.state) && task.candidateGroups.contains("LABORATORIO")) {
                result.add(describe(task, false));
            }
        }
        return result;
    }

    public DiagnosticTask detail(String key) throws Exception {
        return describe(openTask(key));
    }

    private DiagnosticTask describe(CamundaRestClient.UserTask task) throws Exception {
        return describe(task, true);
    }

    private DiagnosticTask describe(CamundaRestClient.UserTask task, boolean includeProposal) throws Exception {
        Map<String, Object> vars = rest.getTypedTaskVariables(task.userTaskKey);
        var saved = store.find(task.userTaskKey).orElse(null);
        DiagnosticRequestContext request = null;
        String contextError = null;
        try {
            request = DiagnosticRequestContext.from(task.elementId, vars);
            if (saved != null && !saved.getRequesterRole().equals(request.requesterRole())) {
                throw conflict("La richiesta e' cambiata rispetto alla decisione salvata.");
            }
        } catch (ResponseStatusException exception) {
            contextError = exception.getReason();
        }
        SlotSuggestion proposed = null;
        String proposalMessage = null;
        if (request != null && saved == null && includeProposal) {
            if ("MANUAL_CALENDAR".equals(request.schedulingPreference())) {
                try {
                    LocalDateTime start = LocalDateTime.parse(request.proposedStart());
                    Long resource = Long.valueOf(request.proposedResourceId());
                    if (!start.toLocalDate().isBefore(LocalDate.now())
                            && !start.toLocalDate().isAfter(LocalDate.now().plusDays(9))) {
                        proposed = exams.slots(request.examType(), request.bodyPart(), start.toLocalDate()).stream()
                                .filter(slot -> slot.getStartTime().equals(start) && slot.getResourceId().equals(resource))
                                .findFirst().orElse(null);
                    }
                    if (proposed == null) proposalMessage = "La fascia proposta dallo specialista non e' piu' disponibile. Scegli un'alternativa motivata.";
                } catch (java.time.format.DateTimeParseException | NumberFormatException exception) {
                    proposalMessage = "La proposta dello specialista non e' valida. Scegli un'alternativa motivata.";
                }
            } else if ("AUTO_FIRST_AVAILABLE".equals(request.schedulingPreference())) {
                for (int offset = 0; offset < 10 && proposed == null; offset++) {
                    proposed = exams.slots(request.examType(), request.bodyPart(), LocalDate.now().plusDays(offset))
                            .stream().findFirst().orElse(null);
                }
                if (proposed == null) proposalMessage = "Nessuna disponibilita' nei prossimi 10 giorni.";
            } else contextError = "Modalita' di schedulazione non supportata.";
        }
        return new DiagnosticTask(task.userTaskKey, task.processInstanceKey, task.assignee,
                DiagnosticRequestContext.text(vars, "patientID"), DiagnosticRequestContext.text(vars, "patientData"),
                DiagnosticRequestContext.text(vars, "diagnosiCode"), request, contextError, proposed, proposalMessage,
                saved == null ? null : decisionView(saved));
    }

    public SpecialistTaskService.TaskActionResult claim(String key) throws Exception {
        var task = openTask(key);
        if (task.assignee != null && !task.assignee.isBlank() && !OPERATOR.equals(task.assignee)) {
            throw conflict("L'attivita' e' assegnata a un altro operatore.");
        }
        DiagnosticRequestContext.from(task.elementId, rest.getTypedTaskVariables(key));
        brokerClaim(key);
        return new SpecialistTaskService.TaskActionResult(key, "CLAIMED", OPERATOR);
    }

    public BookingResult decide(String key, DiagnosticBookingCommand command) throws Exception {
        if (!command.isValidCommand()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decisione diagnostica non valida.");
        var task = openTask(key);
        if (!OPERATOR.equals(task.assignee)) throw conflict("Prendi in carico l'attivita' e attendi la conferma.");
        var vars = rest.getTypedTaskVariables(key);
        var request = DiagnosticRequestContext.from(task.elementId, vars);
        var existing = store.find(key).orElse(null);
        if (existing != null) {
            if (existing.getAction() == DiagnosticBookingCommand.Action.REJECT
                    && command.action() == existing.getAction() && command.reason().equals(existing.getReason())
                    && existing.getRequesterRole().equals(request.requesterRole())) {
                return completeRejection(key, request, existing, vars);
            }
            throw conflict("Decisione gia' salvata. Aggiorna il dettaglio, senza creare altre prenotazioni.");
        }
        SlotSuggestion selected = null;
        if (command.action() != DiagnosticBookingCommand.Action.REJECT) {
            selected = exams.slots(request.examType(), request.bodyPart(), command.startTime().toLocalDate()).stream()
                    .filter(slot -> slot.getResourceId().equals(command.resourceId()) && slot.getStartTime().equals(command.startTime()))
                    .findFirst().orElseThrow(() -> conflict("Fascia non piu' disponibile. Aggiorna il calendario."));
            if (command.action() == DiagnosticBookingCommand.Action.CONFIRM) {
                var proposal = describe(task).proposal();
                if (proposal == null || !proposal.getResourceId().equals(selected.getResourceId())
                        || !proposal.getStartTime().equals(selected.getStartTime())) {
                    throw conflict("La proposta e' cambiata. Aggiorna il dettaglio o scegli Modifica con una motivazione.");
                }
            }
        }
        brokerClaim(key);
        var saved = store.save(key, task.processInstanceKey, DiagnosticRequestContext.text(vars, "patientID"),
                request, command, selected);
        if (command.action() == DiagnosticBookingCommand.Action.REJECT) return completeRejection(key, request, saved, vars);
        return new BookingResult("BOOKED", decisionView(saved));
    }

    private BookingResult completeRejection(String key, DiagnosticRequestContext request,
            DiagnosticBookingDecision decision, Map<String, Object> vars) throws Exception {
        brokerClaim(key);
        String prefix = request.requesterRole().equals("CHIRURGHI") ? "chirurgo" : "oncologo";
        String message = "Esame rifiutato dalla diagnostica: " + decision.getReason();
        Map<String, Object> result = new HashMap<>();
        result.put("examResult", message);
        result.put(prefix + "ExamResult", message);
        result.put(prefix + "RequiresExam", false);
        result.put("requesterRole", request.requesterRole());
        result.put("examRejected", true);
        result.put("examRejectionReason", decision.getReason());
        result.put("diagnosticReporterId", OPERATOR);
        result.put("diagnosticReporterName", "Mario Neri");
        result.put("diagnosticReporterRole", "Tecnico Laboratorio");
        String queue = DiagnosticRequestContext.text(vars, "examQueue");
        result.put("examQueue", java.util.Arrays.stream(queue.split(",")).map(String::trim)
                .filter(role -> !role.isBlank() && !role.equals(request.requesterRole()))
                .collect(java.util.stream.Collectors.joining(",")));
        try {
            rest.completeTask(key, result);
        } catch (HttpClientErrorException exception) {
            throw commandFailure(exception);
        }
        return new BookingResult("COMPLETED", decisionView(decision));
    }

    private CamundaRestClient.UserTask openTask(String key) throws Exception {
        var task = rest.searchTasks(Map.of("filter", Map.of("userTaskKey", key))).stream()
                .filter(item -> key.equals(item.userTaskKey) && item.candidateGroups.contains("LABORATORIO"))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task diagnostico non trovato."));
        if (!"CREATED".equals(task.state)) throw conflict("Il task diagnostico non e' piu' aperto.");
        if (!List.of("Activity_1nqhbxs", "Activity_diagnostic_oncologo").contains(task.elementId)) {
            throw conflict("Il task non e' un'attivita' diagnostica supportata.");
        }
        return task;
    }

    private void brokerClaim(String key) throws Exception {
        try {
            rest.claimTask(key, OPERATOR);
        } catch (HttpClientErrorException exception) {
            throw commandFailure(exception);
        }
    }

    private RuntimeException commandFailure(HttpClientErrorException exception) {
        return exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409
                ? conflict("Il task e' cambiato o e' gia' chiuso. Aggiorna lo stato.") : exception;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static DecisionView decisionView(DiagnosticBookingDecision decision) {
        var slot = decision.getSlot();
        return new DecisionView(decision.getAction(), decision.getReason(),
                slot == null ? null : slot.getId().toString(),
                slot == null ? null : new SlotSuggestion(slot.getResource(), slot.getExamProtocol(), slot.getStartTime(), slot.getEndTime()),
                slot == null ? null : slot.getStatus(), decision.getDecidedAt());
    }

    public record DiagnosticTask(String taskKey, String processInstanceKey, String assignee, String patientID,
            String patientData, String diagnosiCode, DiagnosticRequestContext request, String contextError,
            SlotSuggestion proposal, String proposalMessage, DecisionView decision) {}
    public record DecisionView(DiagnosticBookingCommand.Action action, String reason, String slotId,
            SlotSuggestion slot, SlotStatus slotStatus, LocalDateTime decidedAt) {}
    public record BookingResult(String status, DecisionView decision) {}
}
