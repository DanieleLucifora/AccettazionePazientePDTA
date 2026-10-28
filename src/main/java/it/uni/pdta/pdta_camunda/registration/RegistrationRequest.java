package it.uni.pdta.pdta_camunda.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.AssertTrue;
import java.time.LocalDate;
import java.net.URI;

import java.util.HashMap;
import java.util.Map;

public record RegistrationRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String patientID,
        @NotBlank @Size(max = 100) @Pattern(regexp = ".*\\p{L}.*") String firstName,
        @NotBlank @Size(max = 100) @Pattern(regexp = ".*\\p{L}.*") String lastName,
        @NotNull @PastOrPresent LocalDate birthDate,
        @NotBlank @Size(max = 64) String diagnosiCode,
        @Size(max = 2048) String clinicalFileUrl) {

    public RegistrationRequest {
        patientID = patientID == null ? null : patientID.trim();
        firstName = normalizeName(firstName);
        lastName = normalizeName(lastName);
        diagnosiCode = diagnosiCode == null ? null : diagnosiCode.trim();
        clinicalFileUrl = clinicalFileUrl == null ? "" : clinicalFileUrl.trim();
    }

    private static String normalizeName(String name) {
        return name == null ? null : name.trim().replaceAll("\\s+", " ");
    }

    @AssertTrue(message = "Nome e cognome non possono contenere caratteri di controllo")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isValidNames() {
        return (firstName == null || firstName.codePoints().noneMatch(Character::isISOControl))
                && (lastName == null || lastName.codePoints().noneMatch(Character::isISOControl));
    }

    @AssertTrue(message = "Selezionare un codice diagnosi previsto dalla DMN")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isKnownDiagnosis() { return diagnosiCode != null && DiagnosisCatalog.codes().contains(diagnosiCode); }

    @AssertTrue(message = "Anno di nascita non valido")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isValidBirthYear() { return birthDate == null || birthDate.getYear() > 0; }

    @AssertTrue(message = "Usare un URL assoluto http o https senza credenziali")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSafeClinicalUrl() {
        if (clinicalFileUrl.isBlank()) return true;
        try {
            var uri = new URI(clinicalFileUrl);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (java.net.URISyntaxException exception) { return false; }
    }

    public String patientData() { return firstName + " " + lastName; }

    public Map<String, Object> variables(String caseManagerId) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("patientID", patientID);
        variables.put("patientData", patientData());
        variables.put("patientFirstName", firstName);
        variables.put("patientLastName", lastName);
        variables.put("patientBirthDate", birthDate.toString());
        variables.put("registrationSchemaVersion", 2);
        variables.put("diagnosiCode", diagnosiCode);
        variables.put("caseManagerId", caseManagerId);
        if (!clinicalFileUrl.isBlank()) {
            variables.put("clinicalFileUrl", clinicalFileUrl);
        }
        return variables;
    }
}
