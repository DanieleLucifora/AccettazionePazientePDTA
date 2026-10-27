package it.uni.pdta.pdta_camunda.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DiagnosticReportCommand(@NotBlank @Size(max = 10000) String text) {
    public DiagnosticReportCommand {
        text = text == null ? "" : text.trim();
    }

    public boolean valid() {
        return !text.isBlank() && text.length() <= 10000;
    }
}
