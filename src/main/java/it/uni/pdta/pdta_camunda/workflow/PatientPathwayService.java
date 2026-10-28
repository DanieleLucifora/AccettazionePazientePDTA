package it.uni.pdta.pdta_camunda.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PatientPathwayService {
    private static final String PROCESS = "presa_in_carico_pdta";
    private final CamundaRestClient rest;
    private final DiagnosticBookingStore bookings;

    public PatientPathwayService(CamundaRestClient rest, DiagnosticBookingStore bookings) {
        this.rest = rest;
        this.bookings = bookings;
    }

    public PathwayPage list(String patientId, String state, int offset) throws Exception {
        Map<String, Object> filter = new HashMap<>();
        filter.put("processDefinitionId", PROCESS);
        if (!patientId.isBlank()) {
            filter.put("variables", List.of(Map.of("name", "patientID",
                    "value", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(patientId))));
        }
        if (!state.isBlank()) filter.put("state", state);
        var page = rest.searchProcessInstances(Map.of("filter", filter,
                "sort", List.of(Map.of("field", "startDate", "order", "DESC")),
                "page", Map.of("from", offset, "limit", 20)));
        if (!page.path("items").isArray() || !page.path("page").path("totalItems").canConvertToInt()) {
            throw new IllegalStateException("Paginazione percorsi Camunda non valida.");
        }
        List<PathwaySummary> items = new ArrayList<>();
        for (var process : page.path("items")) {
            if (!PROCESS.equals(process.path("processDefinitionId").asText())) {
                throw new IllegalStateException("Camunda ha restituito un processo estraneo al PDTA.");
            }
            var item = summary(process, rest.getProcessVariables(process.path("processInstanceKey").asText()));
            if ((!patientId.isBlank() && !patientId.equals(item.patientID())) || (!state.isBlank() && !state.equals(item.state()))) {
                throw new IllegalStateException("Gli indici Camunda non sono coerenti con i filtri. Aggiornare la ricerca.");
            }
            items.add(item);
        }
        int total = page.path("page").path("totalItems").asInt();
        return new PathwayPage(items, offset, total, offset + items.size() < total);
    }

    public PathwayDetail detail(String key) throws Exception {
        JsonNode process;
        try {
            process = rest.getProcessInstance(key);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Percorso PDTA non trovato nello storico Camunda.");
        }
        if (!key.equals(process.path("processInstanceKey").asText())
                || !PROCESS.equals(process.path("processDefinitionId").asText())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Percorso PDTA non trovato.");
        }
        var vars = rest.getProcessVariables(key);
        var elements = rest.getProcessElements(key).stream().map(node -> new PathwayElement(
                node.path("elementInstanceKey").asText(), node.path("elementId").asText(),
                node.path("elementName").asText(), node.path("type").asText(), node.path("state").asText(),
                node.path("startDate").asText(""), node.path("endDate").asText(""), node.path("hasIncident").asBoolean()))
                .sorted(Comparator.comparing(PathwayElement::startDate).thenComparing(PathwayElement::key)).toList();
        var tasks = rest.searchTasks(Map.of("filter", Map.of("processInstanceKey", key)));
        var taskViews = tasks.stream().map(task -> new PathwayTask(task.userTaskKey, task.name, task.elementId,
                task.state, task.assignee, task.candidateGroups, task.creationDate, task.completionDate))
                .sorted(Comparator.comparing(task -> task.createdAt() == null ? "" : task.createdAt())).toList();
        var decisions = bookings.forProcess(key).stream().map(item -> new PathwayExam(item.getTaskKey(),
                item.getRequesterRole(), DiagnosticTaskService.decisionView(item),
                item.getReportText() == null ? null : DiagnosticReportService.view(item))).toList();
        boolean excluded = completed(elements, "Event_0fff0ni");
        boolean included = completed(elements, "Event_1xfnruz");
        String outcome = included ? "PDTA_COMPLETED" : excluded ? "BRANCH_REJECTED" : "NOT_DETERMINED";
        return new PathwayDetail(summary(process, vars), outcome, taskViews, elements, decisions, bookings.historicalSlots(key),
                specialist(vars, "chirurgo", "CHIRURGHI"), specialist(vars, "oncologo", "ONCOLOGI"));
    }

    private boolean completed(List<PathwayElement> elements, String id) {
        return elements.stream().anyMatch(element -> id.equals(element.elementId()) && "COMPLETED".equals(element.state()));
    }

    private SpecialistProgress specialist(Map<String, Object> vars, String prefix, String role) {
        return new SpecialistProgress(role, booleanValue(vars.get(prefix + "Approved")),
                booleanValue(vars.get(prefix + "RequiresExam")), text(vars, prefix + "Note"),
                text(vars, prefix + "ExamResult"));
    }

    private Boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private PathwaySummary summary(JsonNode process, Map<String, Object> vars) {
        if (!process.path("processInstanceKey").asText().matches("[1-9][0-9]{0,18}")
                || !List.of("ACTIVE", "COMPLETED", "TERMINATED").contains(process.path("state").asText())) {
            throw new IllegalStateException("Stato o chiave del percorso Camunda non validi.");
        }
        return new PathwaySummary(process.path("processInstanceKey").asText(), process.path("state").asText(),
                process.path("hasIncident").asBoolean(), process.path("processDefinitionVersion").asInt(),
                process.path("startDate").asText(""), process.path("endDate").asText(""),
                text(vars, "patientID"), text(vars, "patientData"), text(vars, "patientFirstName"),
                text(vars, "patientLastName"), text(vars, "patientBirthDate"), text(vars, "diagnosiCode"));
    }

    private String text(Map<String, Object> vars, String key) { return DiagnosticRequestContext.text(vars, key); }

    public record PathwayPage(List<PathwaySummary> items, int offset, int total, boolean hasNext) {}
    public record PathwaySummary(String key, String state, boolean hasIncident, int version, String startDate,
            String endDate, String patientID, String patientData, String firstName, String lastName,
            String birthDate, String diagnosiCode) {}
    public record PathwayDetail(PathwaySummary pathway, String outcome, List<PathwayTask> tasks,
            List<PathwayElement> elements, List<PathwayExam> exams, List<DiagnosticBookingStore.HistoricalSlot> historicalSlots,
            SpecialistProgress surgeon, SpecialistProgress oncologist) {}
    public record PathwayTask(String key, String name, String elementId, String state, String assignee,
            List<String> groups, String createdAt, String completedAt) {}
    public record PathwayElement(String key, String elementId, String name, String type, String state,
            String startDate, String endDate, boolean hasIncident) {}
    public record PathwayExam(String taskKey, String requesterRole, DiagnosticTaskService.DecisionView decision,
            DiagnosticReportService.ReportView report) {}
    public record SpecialistProgress(String role, Boolean approved, Boolean examRequired, String note, String examResult) {}
}
