package it.uni.pdta.pdta_camunda.workflow;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SpecialistDecision(@NotNull Outcome outcome, @Size(max = 2000) String note) {
    public SpecialistDecision {
        note = note == null ? "" : note.trim();
    }

    @AssertTrue(message = "La motivazione e' obbligatoria per il rifiuto")
    public boolean isValidDecision() {
        return outcome != null && note.length() <= 2000 && (outcome != Outcome.REJECT || !note.isBlank());
    }

    public Map<String, Object> variables(String operatorId, String validatorRole) {
        return Map.of("isApproved", outcome == Outcome.APPROVE, "requiresExam", false,
                "note", note, "validatorId", operatorId, "validatorRole", validatorRole);
    }

    public enum Outcome { APPROVE, REJECT }
}
