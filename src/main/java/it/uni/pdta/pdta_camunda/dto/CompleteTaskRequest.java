package it.uni.pdta.pdta_camunda.dto;

import java.util.HashMap;
import java.util.Map;

// DTO per completare un user task (es. revisione GOM1/GOM2)
public class CompleteTaskRequest {

    private Map<String, Object> variables;

    // Costruttori
    public CompleteTaskRequest() {
        this.variables = new HashMap<>();
    }

    public CompleteTaskRequest(Map<String, Object> variables) {
        this.variables = variables != null ? variables : new HashMap<>();
    }

    // Getters e Setters
    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    // Metodi helper per costruire le variabili facilmente
    public CompleteTaskRequest addVariable(String key, Object value) {
        this.variables.put(key, value);
        return this;
    }

    @Override
    public String toString() {
        return "CompleteTaskRequest{" +
                "variables=" + variables +
                '}';
    }
}
