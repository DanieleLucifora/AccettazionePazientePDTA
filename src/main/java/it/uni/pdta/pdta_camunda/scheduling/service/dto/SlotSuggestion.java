package it.uni.pdta.pdta_camunda.scheduling.service.dto;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DTO che rappresenta uno slot orario disponibile per un esame.
 * Usato sia per la proposta automatica (primo slot libero) sia per il
 * calendario manuale (lista slot disponibili in un giorno).
 */
@Getter
public class SlotSuggestion {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Long resourceId;
    private final String resourceCode;
    private final String resourceName;
    private final Long protocolId;
    private final String examDisplayName;
    private final int totalSlotMinutes;
    private final boolean requiresContrast;
    private final boolean requiresFasting;
    private final String notes;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;

    public SlotSuggestion(DiagnosticResource resource, ExamProtocol protocol,
                          LocalDateTime startTime, LocalDateTime endTime) {
        this.resourceId = resource.getId();
        this.resourceCode = resource.getCode();
        this.resourceName = resource.getName();
        this.protocolId = protocol.getId();
        this.examDisplayName = protocol.getDisplayName();
        this.totalSlotMinutes = protocol.getTotalSlotMinutes();
        this.requiresContrast = protocol.isRequiresContrast();
        this.requiresFasting = protocol.isRequiresFasting();
        this.notes = protocol.getNotes();
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * Stringa formattata per la visualizzazione CLI:
     *   [TAC-01] TAC Torace  05/04/2026 08:00 → 08:35  (35 min)  ⚠ mdc  ⚠ digiuno
     */
    public String toCliDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  [%-8s] %-30s  %s → %s  (%d min)",
                resourceCode,
                examDisplayName,
                startTime.format(FMT),
                endTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                totalSlotMinutes));
        if (requiresContrast) sb.append("  [mdc]");
        if (requiresFasting)  sb.append("  [digiuno]");
        if (notes != null && !notes.isBlank()) sb.append("  | ").append(notes);
        return sb.toString();
    }
}
