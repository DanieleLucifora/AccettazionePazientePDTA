package it.uni.pdta.pdta_camunda.dto;

// DTO per la richiesta di avvio di un nuovo processo PDTA
public class StartProcessRequest {

    private String patientID;
    private String patientData;      
    private String diagnosiCode;

    // Costruttori
    public StartProcessRequest() {
    }

    public StartProcessRequest(String patientID, String patientData, String diagnosiCode) {
        this.patientID = patientID;
        this.patientData = patientData;
        this.diagnosiCode = diagnosiCode;
    }

    // Getters e Setters
    public String getPatientID() {
        return patientID;
    }

    public void setPatientID(String patientID) {
        this.patientID = patientID;
    }

    public String getPatientData() {
        return patientData;
    }

    public void setPatientData(String patientData) {
        this.patientData = patientData;
    }

    public String getDiagnosiCode() {
        return diagnosiCode;
    }

    public void setDiagnosiCode(String diagnosiCode) {
        this.diagnosiCode = diagnosiCode;
    }


    @Override
    public String toString() {
        return "StartProcessRequest{" +
                "patientID='" + patientID + '\'' +
                ", patientData='" + patientData + '\'' +
                ", diagnosiCode='" + diagnosiCode + '\'' +
                '}';
    }
}
