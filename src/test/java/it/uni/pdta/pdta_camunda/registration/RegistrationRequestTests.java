package it.uni.pdta.pdta_camunda.registration;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationRequestTests {
    @Test
    void normalizesUnicodeNamesAndPreservesIdentifierAndLegacyVariables() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var input = new RegistrationRequest(" 0001 ", "  Chloé ", " D'Amico  Verdi ",
                    LocalDate.of(1980, 1, 2), " 6401 ", null);
            assertThat(factory.getValidator().validate(input)).isEmpty();
            assertThat(input.variables("CM001")).containsEntry("patientID", "0001")
                    .containsEntry("patientData", "Chloé D'Amico Verdi")
                    .containsEntry("patientFirstName", "Chloé").containsEntry("patientLastName", "D'Amico Verdi")
                    .containsEntry("patientBirthDate", "1980-01-02").containsEntry("registrationSchemaVersion", 2);
            assertThat(DiagnosisCatalog.codes()).containsExactly("1001", "2609", "3202", "5201", "6401", "7001");
        }
    }

    @Test
    void rejectsIncompleteOrInvalidDataAtSharedServiceBoundaryBeforeCreatingAProcess() {
        var client = mock(io.camunda.client.CamundaClient.class);
        var rest = mock(it.uni.pdta.pdta_camunda.workflow.CamundaRestClient.class);
        var service = new PatientRegistrationService(client, rest);
        var day = LocalDate.of(1980, 1, 2);
        for (var input : java.util.List.of(
                new RegistrationRequest("ID SPACE", "Nome", "Cognome", day, "6401", ""),
                new RegistrationRequest("001", " ", "Cognome", day, "6401", ""),
                new RegistrationRequest("001", "123", "Cognome", day, "6401", ""),
                new RegistrationRequest("001", "Nome", "", day, "6401", ""),
                new RegistrationRequest("001", "Nome", "Cognome", null, "6401", ""),
                new RegistrationRequest("001", "Nome", "Cognome", LocalDate.now().plusDays(1), "6401", ""),
                new RegistrationRequest("001", "Nome", "Cognome", LocalDate.of(0, 1, 1), "6401", ""),
                new RegistrationRequest("001", "Nome", "Cognome", day, "UNKNOWN", ""),
                new RegistrationRequest("001", "Nome", "Cognome", day, null, ""),
                new RegistrationRequest("001", "Nome", "Cognome", day, "6401", "https://user:secret@example.test/a"),
                new RegistrationRequest("001", "Nome", "Cognome", day, "6401", "https:/missing-host"),
                new RegistrationRequest("001", "Nome", "Cognome", day, "6401", "javascript:alert(1)"))) {
            assertThatThrownBy(() -> service.start(input, "CM001"))
                    .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        }
        verifyNoInteractions(client, rest);
    }
}
