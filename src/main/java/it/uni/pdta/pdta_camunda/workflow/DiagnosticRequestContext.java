package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

public record DiagnosticRequestContext(String requesterRole, String requesterId, String requesterName,
        ExamType examType, BodyPart bodyPart, String schedulingPreference, String note,
        String proposedStart, String proposedResourceId) {
    public static DiagnosticRequestContext from(String elementId, Map<String, Object> vars) {
        String role;
        if ("Activity_diagnostic_oncologo".equals(elementId)) role = "ONCOLOGI";
        else if ("Activity_1nqhbxs".equals(elementId)) {
            // Older process versions share one laboratory element: accept only an unambiguous request.
            boolean surgeon = pending(vars, "chirurgoRequiresExam");
            boolean oncologist = pending(vars, "oncologoRequiresExam");
            if ("CHIRURGHI".equals(text(vars, "diagnosticRequestRole"))) role = "CHIRURGHI";
            else {
                if (surgeon == oncologist) throw conflict("Richiesta non identificabile: verificare la versione BPMN e le richieste pendenti.");
                role = surgeon ? "CHIRURGHI" : "ONCOLOGI";
            }
        } else throw conflict("Il task non e' un'attivita' diagnostica supportata.");
        String prefix = role.equals("CHIRURGHI") ? "chirurgo" : "oncologo";
        // The new BPMN binds the request locally, including when both branches are pending.
        String localRole = text(vars, "diagnosticRequestRole");
        if (!localRole.isBlank()) {
            if (!localRole.equals(role)) throw conflict("Il contesto del richiedente non coincide con il task.");
        }
        if (!pending(vars, prefix + "RequiresExam")) throw conflict("La richiesta non e' piu' pendente.");
        try {
            return new DiagnosticRequestContext(role, text(vars, prefix + "RequesterId"),
                    text(vars, prefix + "RequesterName"), ExamType.valueOf(text(vars, prefix + "ExamType")),
                    BodyPart.valueOf(text(vars, prefix + "BodyPart")),
                    text(vars, prefix + "SchedulingPreference"), text(vars, prefix + "Note"),
                    text(vars, prefix + "SelectedSlotStart"), text(vars, prefix + "SelectedResourceId"));
        } catch (IllegalArgumentException exception) {
            throw conflict("Tipo esame o parte corporea non validi nella richiesta.");
        }
    }

    public static String text(Map<String, Object> vars, String name) {
        Object value = vars.get(name);
        return value == null ? "" : value.toString();
    }

    private static boolean pending(Map<String, Object> vars, String name) {
        return Boolean.parseBoolean(text(vars, name));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
