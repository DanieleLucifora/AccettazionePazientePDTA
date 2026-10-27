package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public record DiagnosticExamRequest(
        @NotNull ExamType examType, @NotNull BodyPart bodyPart,
        @NotNull SchedulingPreference schedulingPreference,
        Long resourceId, LocalDateTime startTime, @Size(max = 2000) String note) {
    public DiagnosticExamRequest {
        note = note == null ? "" : note.trim();
    }

    @AssertTrue(message = "Selezionare una fascia per il calendario manuale; nessuna fascia per la modalita' automatica")
    public boolean isValidRequest() {
        return examType != null && bodyPart != null && schedulingPreference != null && note.length() <= 2000
                && (schedulingPreference == SchedulingPreference.MANUAL_CALENDAR
                ? resourceId != null && resourceId > 0 && startTime != null
                : resourceId == null && startTime == null);
    }

    public Map<String, Object> variables(SpecialistTaskService.SpecialistRole role, String operatorId,
            String operatorName, String validatorRole, Object currentQueue, SlotSuggestion slot) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("isApproved", false);
        variables.put("requiresExam", true);
        variables.put("note", note);
        variables.put("examType", examType.name());
        variables.put("bodyPart", bodyPart.name());
        variables.put("schedulingPreference", schedulingPreference.name());
        // Clear inherited manual selections when requesting a new automatic exam.
        variables.put("preferredExamDate", slot == null ? "" : slot.getStartTime().toLocalDate().toString());
        variables.put("specialistSelectedSlotStart", slot == null ? "" : slot.getStartTime().toString());
        variables.put("specialistSelectedSlotEnd", slot == null ? "" : slot.getEndTime().toString());
        variables.put("specialistSelectedResourceId", slot == null ? "" : slot.getResourceId().toString());
        variables.put("specialistSelectedProtocolId", slot == null ? "" : slot.getProtocolId().toString());
        variables.put("specialistSelectedResourceCode", slot == null ? "" : slot.getResourceCode());
        variables.put("requesterId", operatorId);
        variables.put("requesterName", operatorName);
        variables.put("validatorId", operatorId);
        variables.put("validatorRole", validatorRole);
        LinkedHashSet<String> queue = new LinkedHashSet<>();
        if (currentQueue != null) {
            for (String token : currentQueue.toString().split(",")) {
                if (!token.isBlank()) queue.add(token.trim());
            }
        }
        queue.add(role.name());
        variables.put("examQueue", String.join(",", queue));
        String prefix = role == SpecialistTaskService.SpecialistRole.CHIRURGHI ? "chirurgo" : "oncologo";
        variables.put(prefix + "ExamType", examType.name());
        variables.put(prefix + "BodyPart", bodyPart.name());
        variables.put(prefix + "RequesterName", operatorName);
        variables.put(prefix + "RequesterId", operatorId);
        variables.put(prefix + "SchedulingPreference", schedulingPreference.name());
        variables.put(prefix + "PreferredExamDate", variables.get("preferredExamDate"));
        for (String suffix : new String[]{"SlotStart", "SlotEnd", "ResourceId", "ProtocolId", "ResourceCode"}) {
            variables.put(prefix + "Selected" + suffix, variables.get("specialistSelected" + suffix));
        }
        return variables;
    }

    public enum SchedulingPreference { AUTO_FIRST_AVAILABLE, MANUAL_CALENDAR }
}
