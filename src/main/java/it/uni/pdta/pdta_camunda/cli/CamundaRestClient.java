package it.uni.pdta.pdta_camunda.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

// Client per Camunda 8 REST API v2

public class CamundaRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(CamundaRestClient.class);

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CamundaRestClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    // Cerca user task con filtri
    public List<UserTask> searchTasks(Map<String, Object> filter) throws Exception {
        String url = baseUrl + "/user-tasks/search";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(filter, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode itemsNode = root.path("items");

        List<UserTask> tasks = new ArrayList<>();
        if (itemsNode.isArray()) {
            for (JsonNode taskNode : itemsNode) {
                UserTask task = new UserTask();
                task.userTaskKey = taskNode.path("userTaskKey").asText();
                task.name = taskNode.path("name").asText();
                task.state = taskNode.path("state").asText();
                task.assignee = taskNode.path("assignee").asText(null);
                task.processInstanceKey = taskNode.path("processInstanceKey").asText();
                task.elementId = taskNode.path("elementId").asText();
                
                JsonNode candidateGroupsNode = taskNode.path("candidateGroups");
                if (candidateGroupsNode.isArray()) {
                    for (JsonNode group : candidateGroupsNode) {
                        task.candidateGroups.add(group.asText());
                    }
                }
                
                tasks.add(task);
            }
        }

        return tasks;
    }

    // Assegna un task a un utente
    public void assignTask(String userTaskKey, String assignee) throws Exception {
        String url = baseUrl + "/user-tasks/" + userTaskKey + "/assignment";

        Map<String, String> body = new HashMap<>();
        body.put("assignee", assignee);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        restTemplate.postForEntity(url, request, String.class);
        LOG.info("Task {} assigned to {}", userTaskKey, assignee);
    }

    // Completa un task con variabili
    public void completeTask(String userTaskKey, Map<String, Object> variables) throws Exception {
        String url = baseUrl + "/user-tasks/" + userTaskKey + "/completion";

        Map<String, Object> body = new HashMap<>();
        body.put("variables", variables);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        restTemplate.postForEntity(url, request, String.class);
        LOG.info("Task {} completed with variables: {}", userTaskKey, variables.keySet());
    }

    // Recupera le variabili di un task
    public Map<String, Object> getTaskVariables(String userTaskKey) throws Exception {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
                
        // Tentativo con search variables
        String searchUrl = baseUrl + "/user-tasks/" + userTaskKey + "/variables/search";
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(new HashMap<>(), headers);
        
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(searchUrl, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode items = root.path("items");
            
            Map<String, Object> variables = new HashMap<>();
            if (items.isArray()) {
                for (JsonNode varNode : items) {
                    String name = varNode.path("name").asText();
                    String value = varNode.path("value").asText(); 
                    variables.put(name, value.replace("\"", "")); 
                }
            }
            return variables;
        } catch (Exception e) {
            LOG.warn("Impossibile recuperare variabili per task {}: {}", userTaskKey, e.getMessage());
            return new HashMap<>();
        }
    }

    // Verifica lo stato di un'istanza di processo
    public String getProcessInstanceState(String processInstanceKey) throws Exception {
        // Usa endpoint diretto invece di search per ottenere stato affidabile
        String url = baseUrl + "/process-instances/" + processInstanceKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String state = root.path("state").asText();
            LOG.debug("Process {} state: {}", processInstanceKey, state);
            return state;
        } catch (HttpClientErrorException.NotFound e) {
            // 404 significa che il processo è stato completato e rimosso
            LOG.debug("Process {} not found (404) - likely completed and removed", processInstanceKey);
            return "COMPLETED";
        } catch (Exception e) {
            LOG.warn("Error checking process instance state: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    // DTO per rappresentare un user task
    public static class UserTask {
        public String userTaskKey;
        public String name;
        public String state;
        public String assignee;
        public String processInstanceKey;
        public String elementId;
        public List<String> candidateGroups = new ArrayList<>();
        public Map<String, Object> variables = new HashMap<>();

        @Override
        public String toString() {
            return String.format("UserTask[key=%s, name=%s, state=%s, assignee=%s]",
                userTaskKey, name, state, assignee);
        }
    }
}
