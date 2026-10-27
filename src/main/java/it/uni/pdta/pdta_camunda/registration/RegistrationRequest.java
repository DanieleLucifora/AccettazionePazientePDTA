package it.uni.pdta.pdta_camunda.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.Map;

public record RegistrationRequest(
        @NotBlank @Size(max = 64) String patientID,
        @NotBlank @Size(max = 2000) String patientData,
        @NotBlank @Size(max = 64) String diagnosiCode,
        @Size(max = 2048)
        @Pattern(regexp = "^$|https?://[^\\s]+", message = "Usare un URL http o https") String clinicalFileUrl) {

    public RegistrationRequest {
        patientID = patientID == null ? null : patientID.trim();
        patientData = patientData == null ? null : patientData.trim();
        diagnosiCode = diagnosiCode == null ? null : diagnosiCode.trim();
        clinicalFileUrl = clinicalFileUrl == null ? "" : clinicalFileUrl.trim();
    }

    public Map<String, Object> variables(String caseManagerId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("patientID", patientID);
        variables.put("patientData", patientData);
        variables.put("diagnosiCode", diagnosiCode);
        variables.put("caseManagerId", caseManagerId);
        if (!clinicalFileUrl.isBlank()) {
            variables.put("clinicalFileUrl", clinicalFileUrl);
        }
        return variables;
    }
}
