package it.uni.pdta.pdta_camunda.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SpecialistTaskService {
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

    public enum SpecialistRole { CHIRURGHI, ONCOLOGI }

    public record TaskSummary(String taskKey, String processInstanceKey, String name, String assignee,
                              String patientID, String patientData, String diagnosiCode) {}
}
