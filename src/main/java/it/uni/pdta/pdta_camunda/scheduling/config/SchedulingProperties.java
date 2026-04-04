package it.uni.pdta.pdta_camunda.scheduling.config;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Proprietà di configurazione per lo scheduling diagnostico.
 * Prefisso YAML: pdta.scheduling
 */
@ConfigurationProperties(prefix = "pdta.scheduling")
@Data
public class SchedulingProperties {

    /** Orario inizio giornata lavorativa (es. "08:00"). */
    private String workDayStart = "08:00";

    /** Orario fine giornata lavorativa (es. "18:00"). */
    private String workDayEnd = "18:00";

    /** Minuti di buffer da aggiungere tra uno slot e il successivo. */
    private int slotBufferMinutes = 5;

    /** Numero massimo di tentativi in caso di conflitto su prenotazione. */
    private int bookingRetryAttempts = 3;

    /** Protocolli di default da caricare al primo avvio. */
    private List<ProtocolDefinition> protocols = new ArrayList<>();

    @Data
    public static class ProtocolDefinition {
        private ExamType examType;
        private BodyPart bodyPart;
        private String displayName;
        private int setupMinutes;
        private int examMinutes;
        private int teardownMinutes;
        private boolean requiresContrast;
        private boolean requiresFasting;
        /** Data di entrata in vigore. Defaults all'avvio se non specificata. */
        private LocalDate effectiveFrom;
        private String notes;
    }
}
