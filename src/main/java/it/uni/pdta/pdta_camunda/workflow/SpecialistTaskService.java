package it.uni.pdta.pdta_camunda.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SpecialistTaskService {
    private static final Logger LOG = LoggerFactory.getLogger(SpecialistTaskService.class);
    private final CamundaRestClient restClient;

    public SpecialistTaskService(CamundaRestClient restClient) {
        this.restClient = restClient;
    }

    public List<TaskSummary> findTasks(SpecialistRole role) throws Exception {
        List<TaskSummary> result = new ArrayList<>();
        List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(
                Map.of("filter", Map.of("state", "CREATED", "candidateGroup", role.name())));
        for (CamundaRestClient.UserTask task : tasks) {
            if (!"CREATED".equals(task.state) || !task.candidateGroups.contains(role.name())) {
                continue;
            }
            Map<String, Object> variables = restClient.getTypedTaskVariables(task.userTaskKey);
            result.add(new TaskSummary(task.userTaskKey, task.processInstanceKey, task.name, task.assignee,
                    text(variables, "patientID"), text(variables, "patientData"), text(variables, "diagnosiCode")));
        }
        return result;
    }

    private String text(Map<String, Object> variables, String name) {
        Object value = variables.get(name);
        return value == null ? "" : value.toString();
    }

    public TaskDetail getTask(String taskKey, SpecialistRole role) throws Exception {
        CamundaRestClient.UserTask task = requireOpenTask(taskKey, role);
        Map<String, Object> variables = restClient.getTypedTaskVariables(taskKey);
        String prefix = role == SpecialistRole.CHIRURGHI ? "chirurgo" : "oncologo";
        String examResult = text(variables, prefix + "ExamResult");
        if (examResult.isBlank() && role.name().equals(text(variables, "requesterRole"))) {
            examResult = text(variables, "examResult");
        }
        return new TaskDetail(task.userTaskKey, task.processInstanceKey, task.name, task.state, task.assignee,
                role, text(variables, "patientID"), text(variables, "patientData"), text(variables, "diagnosiCode"),
                safeClinicalUrl(text(variables, "clinicalFileUrl"), taskKey),
                examResult, text(variables, prefix + "Note"), role.operatorId);
    }

    private CamundaRestClient.UserTask requireOpenTask(String taskKey, SpecialistRole role) throws Exception {
        List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(
                Map.of("filter", Map.of("userTaskKey", taskKey)));
        CamundaRestClient.UserTask task = tasks.stream()
                .filter(item -> taskKey.equals(item.userTaskKey) && item.candidateGroups.contains(role.name()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Attivita' non trovata nel gruppo selezionato."));
        if (!"CREATED".equals(task.state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "L'attivita' non e' piu' aperta. Torna alla lista per aggiornarla.");
        }
        return task;
    }

    public TaskActionResult claim(String taskKey, SpecialistRole role, String operatorId) throws Exception {
        CamundaRestClient.UserTask task = requireDecisionTask(taskKey, role);
        requireAvailableTo(task, operatorId);
        // Even when the index says this operator owns the task, verify against the broker.
        try {
            restClient.claimTask(taskKey, operatorId);
        } catch (HttpClientErrorException exception) {
            throw commandFailure(exception);
        }
        return new TaskActionResult(taskKey, "CLAIMED", operatorId);
    }

    public TaskActionResult decide(String taskKey, SpecialistRole role, String operatorId,
                                   String validatorRole, SpecialistDecision decision) throws Exception {
        if (!decision.isValidDecision()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Esito o motivazione non validi.");
        }
        CamundaRestClient.UserTask task = requireDecisionTask(taskKey, role);
        if (!operatorId.equals(task.assignee)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Prendi in carico l'attivita' e attendi la conferma prima di inviare l'esito.");
        }
        try {
            restClient.claimTask(taskKey, operatorId);
            restClient.completeTask(taskKey, decision.variables(operatorId, validatorRole));
        } catch (HttpClientErrorException exception) {
            throw commandFailure(exception);
        }
        LOG.info("Valutazione {} inviata per task {} da {}", decision.outcome(), taskKey, operatorId);
        return new TaskActionResult(taskKey, "COMPLETED", operatorId);
    }

    private CamundaRestClient.UserTask requireDecisionTask(String taskKey, SpecialistRole role) throws Exception {
        CamundaRestClient.UserTask task = requireOpenTask(taskKey, role);
        if (!role.elementId.equals(task.elementId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'attivita' non e' una valutazione supportata.");
        }
        return task;
    }

    private void requireAvailableTo(CamundaRestClient.UserTask task, String operatorId) {
        if (task.assignee != null && !task.assignee.isBlank() && !operatorId.equals(task.assignee)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L'attivita' e' assegnata a un altro operatore.");
        }
    }

    private RuntimeException commandFailure(HttpClientErrorException exception) {
        if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409) {
            return new ResponseStatusException(HttpStatus.CONFLICT,
                    "L'attivita' e' cambiata o e' gia' stata gestita. Aggiorna la lista prima di riprovare.", exception);
        }
        return exception;
    }

    private String safeClinicalUrl(String value, String taskKey) {
        if (value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null) {
                return uri.toASCIIString();
            }
        } catch (URISyntaxException exception) {
            LOG.debug("Formato URL cartella clinica non valido per task {}", taskKey);
        }
        LOG.warn("URL cartella clinica non utilizzabile per task {}; link omesso", taskKey);
        return null;
    }

    public enum SpecialistRole {
        CHIRURGHI("CH001", "Chirurgo Toracica", "Activity_14jdrgj"),
        ONCOLOGI("ON001", "Oncologo Medico", "Activity_0vww6jw");

        private final String operatorId;
        private final String validatorRole;
        private final String elementId;

        SpecialistRole(String operatorId, String validatorRole, String elementId) {
            this.operatorId = operatorId;
            this.validatorRole = validatorRole;
            this.elementId = elementId;
        }

        public String operatorId() { return operatorId; }
        public String validatorRole() { return validatorRole; }
    }

    public record TaskActionResult(String taskKey, String status, String assignee) {}

    public record TaskSummary(String taskKey, String processInstanceKey, String name, String assignee,
                              String patientID, String patientData, String diagnosiCode) {}

    public record TaskDetail(String taskKey, String processInstanceKey, String name, String state,
                             String assignee, SpecialistRole role, String patientID, String patientData,
                             String diagnosiCode, String clinicalFileUrl, String examResult, String note,
                             String currentOperatorId) {}
}
