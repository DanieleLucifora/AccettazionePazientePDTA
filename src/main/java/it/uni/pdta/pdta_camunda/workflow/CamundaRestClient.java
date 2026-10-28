package it.uni.pdta.pdta_camunda.workflow;

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
        this(baseUrl, new RestTemplate());
    }

    public CamundaRestClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public JsonNode searchProcessInstances(Map<String, Object> body) throws Exception {
        return searchReadPage("/process-instances/search", body);
    }

    public JsonNode getProcessInstance(String key) throws Exception {
        return objectMapper.readTree(restTemplate.getForObject(baseUrl + "/process-instances/" + key, String.class));
    }

    public Map<String, Object> getProcessVariables(String key) throws Exception {
        Map<String, Object> result = new HashMap<>();
        int offset = 0;
        while (true) {
            var root = searchReadPage("/variables/search", Map.of(
                    "filter", Map.of("processInstanceKey", key, "scopeKey", key),
                    "page", Map.of("from", offset, "limit", 100)));
            var items = root.path("items");
            for (var item : items) {
                if (!key.equals(item.path("scopeKey").asText())) continue;
                var full = item.path("isTruncated").asBoolean()
                        ? objectMapper.readTree(restTemplate.getForObject(baseUrl + "/variables/" + item.path("variableKey").asText(), String.class))
                        : item;
                result.put(full.path("name").asText(), objectMapper.readValue(full.path("value").asText(), Object.class));
            }
            offset += items.size();
            if (items.size() < 100) return result;
        }
    }

    public List<JsonNode> getProcessElements(String key) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            var root = searchReadPage("/element-instances/search",
                    Map.of("filter", Map.of("processInstanceKey", key), "page", Map.of("from", offset, "limit", 100)));
            root.path("items").forEach(result::add);
            offset += root.path("items").size();
            if (root.path("items").size() < 100) return result;
        }
    }

    private JsonNode searchReadPage(String path, Map<String, Object> body) throws Exception {
        var root = objectMapper.readTree(restTemplate.postForObject(baseUrl + path, body, String.class));
        if (root == null || !root.path("items").isArray()) {
            throw new IllegalStateException("Risposta di consultazione Camunda priva di items.");
        }
        return root;
    }

    public Map<String, Object> getTypedTaskVariables(String userTaskKey) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        int offset = 0;
        while (true) {
            Map<String, Object> body = Map.of("page", Map.of("from", offset, "limit", 100));
            JsonNode root = objectMapper.readTree(restTemplate.postForObject(
                    baseUrl + "/user-tasks/" + userTaskKey + "/variables/search", body, String.class));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                throw new IllegalStateException("Risposta variabili Camunda priva di items");
            }
            for (JsonNode item : items) {
                Object value = objectMapper.readValue(item.path("value").asText(), Object.class);
                variables.put(item.path("name").asText(), value);
            }
            offset += items.size();
            if (items.size() < 100) {
                return variables;
            }
        }
    }

    // Cerca user task con filtri
    public List<UserTask> searchTasks(Map<String, Object> filter) throws Exception {
        List<UserTask> tasks = new ArrayList<>();
        if (filter != null && filter.containsKey("page")) {
            return searchTaskPage(filter).tasks;
        }

        int offset = 0;
        int pageSize = 100;

        while (true) {
            Map<String, Object> pagedFilter = new HashMap<>();
            if (filter != null) {
                pagedFilter.putAll(filter);
            }

            Map<String, Object> page = new HashMap<>();
            page.put("from", offset);
            page.put("limit", pageSize);
            pagedFilter.put("page", page);

            SearchPage searchPage = searchTaskPage(pagedFilter);
            if (searchPage.tasks.isEmpty()) {
                break;
            }

            tasks.addAll(searchPage.tasks);
            offset += searchPage.tasks.size();

            if (searchPage.totalItems != null && offset >= searchPage.totalItems) {
                break;
            }

            if (searchPage.tasks.size() < pageSize) {
                break;
            }
        }

        return tasks;
    }

    private SearchPage searchTaskPage(Map<String, Object> filter) throws Exception {
        String url = baseUrl + "/user-tasks/search";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(filter, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            throw new IllegalStateException("Risposta task Camunda priva di items");
        }

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
                task.creationDate = taskNode.path("creationDate").asText("");
                task.completionDate = taskNode.path("completionDate").asText("");

                JsonNode candidateGroupsNode = taskNode.path("candidateGroups");
                if (candidateGroupsNode.isArray()) {
                    for (JsonNode group : candidateGroupsNode) {
                        task.candidateGroups.add(group.asText());
                    }
                }

                tasks.add(task);
            }
        }

        JsonNode pageNode = root.path("page");
        Integer totalItems = pageNode.has("totalItems") ? pageNode.path("totalItems").asInt() : null;
        return new SearchPage(tasks, totalItems);
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

    public void claimTask(String userTaskKey, String assignee) {
        restTemplate.postForEntity(baseUrl + "/user-tasks/" + userTaskKey + "/assignment",
                Map.of("assignee", assignee, "allowOverride", false), String.class);
        LOG.info("Task {} claimed by {} without assignment override", userTaskKey, assignee);
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
        public String creationDate;
        public String completionDate;
        public List<String> candidateGroups = new ArrayList<>();
        public Map<String, Object> variables = new HashMap<>();

        @Override
        public String toString() {
            return String.format("UserTask[key=%s, name=%s, state=%s, assignee=%s]",
                userTaskKey, name, state, assignee);
        }
    }

    private record SearchPage(List<UserTask> tasks, Integer totalItems) {
    }
}
