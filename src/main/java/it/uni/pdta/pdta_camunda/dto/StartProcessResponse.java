package it.uni.pdta.pdta_camunda.dto;

// DTO per la risposta dopo l'avvio di un processo PDTA
public class StartProcessResponse {

    private Long processInstanceKey;
    private String bpmnProcessId;
    private Integer version;
    private String status;
    private String message;

    // Costruttori
    public StartProcessResponse() {
    }

    public StartProcessResponse(Long processInstanceKey, String bpmnProcessId, Integer version) {
        this.processInstanceKey = processInstanceKey;
        this.bpmnProcessId = bpmnProcessId;
        this.version = version;
        this.status = "STARTED";
        this.message = "Processo PDTA avviato con successo";
    }

    // Getters e Setters
    public Long getProcessInstanceKey() {
        return processInstanceKey;
    }

    public void setProcessInstanceKey(Long processInstanceKey) {
        this.processInstanceKey = processInstanceKey;
    }

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public void setBpmnProcessId(String bpmnProcessId) {
        this.bpmnProcessId = bpmnProcessId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "StartProcessResponse{" +
                "processInstanceKey=" + processInstanceKey +
                ", bpmnProcessId='" + bpmnProcessId + '\'' +
                ", version=" + version +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}
