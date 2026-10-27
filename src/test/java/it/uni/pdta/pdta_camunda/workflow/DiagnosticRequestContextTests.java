package it.uni.pdta.pdta_camunda.workflow;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DiagnosticRequestContextTests {
    @Test
    void bindsParallelRequestsToTheirOwnElementAndRejectsLegacyAmbiguity() {
        Map<String, Object> vars = new HashMap<>(Map.of(
                "chirurgoRequiresExam", true, "oncologoRequiresExam", true,
                "chirurgoExamType", "TAC", "chirurgoBodyPart", "TORACE",
                "oncologoExamType", "RMN", "oncologoBodyPart", "TESTA"));
        vars.put("diagnosticRequestRole", "CHIRURGHI");
        assertThat(DiagnosticRequestContext.from("Activity_1nqhbxs", vars).examType().name()).isEqualTo("TAC");
        vars.put("diagnosticRequestRole", "ONCOLOGI");
        assertThat(DiagnosticRequestContext.from("Activity_diagnostic_oncologo", vars).examType().name()).isEqualTo("RMN");
        vars.remove("diagnosticRequestRole");
        assertThatThrownBy(() -> DiagnosticRequestContext.from("Activity_1nqhbxs", vars))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        vars.put("oncologoRequiresExam", false);
        assertThat(DiagnosticRequestContext.from("Activity_1nqhbxs", vars).requesterRole()).isEqualTo("CHIRURGHI");
        vars.put("chirurgoExamType", "INVALID");
        assertThatThrownBy(() -> DiagnosticRequestContext.from("Activity_1nqhbxs", vars))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
