package it.uni.pdta.pdta_camunda.workflow;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record DiagnosticBookingCommand(@NotNull Action action, Long resourceId, LocalDateTime startTime,
        @Size(max = 2000) String reason) {
    public DiagnosticBookingCommand {
        reason = reason == null ? "" : reason.trim();
    }

    @AssertTrue(message = "Controllare fascia e motivazione della decisione diagnostica")
    public boolean isValidCommand() {
        if (action == null || reason.length() > 2000) return false;
        if (action == Action.REJECT) return !reason.isBlank() && resourceId == null && startTime == null;
        return resourceId != null && resourceId > 0 && startTime != null
                && (action != Action.MODIFY || !reason.isBlank());
    }

    public enum Action { CONFIRM, MODIFY, REJECT }
}
