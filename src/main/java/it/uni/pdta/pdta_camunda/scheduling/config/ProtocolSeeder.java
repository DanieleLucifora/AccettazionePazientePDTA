package it.uni.pdta.pdta_camunda.scheduling.config;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ExamProtocolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Carica i protocolli di default definiti in application.yml al primo avvio.
 * Se per una coppia (examType, bodyPart) esiste già un protocollo attivo,
 * non viene creato un duplicato.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProtocolSeeder implements ApplicationRunner {

    private final ExamProtocolRepository protocolRepository;
    private final SchedulingProperties schedulingProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<SchedulingProperties.ProtocolDefinition> definitions = schedulingProperties.getProtocols();
        if (definitions.isEmpty()) {
            log.info("[ProtocolSeeder] Nessun protocollo definito in pdta.scheduling.protocols, skip.");
            return;
        }

        int created = 0;
        int skipped = 0;

        for (SchedulingProperties.ProtocolDefinition def : definitions) {
            LocalDate effectiveFrom = def.getEffectiveFrom() != null
                    ? def.getEffectiveFrom()
                    : LocalDate.now();

            // Controlla se esiste già un protocollo attivo per questa coppia
            List<ExamProtocol> existing = protocolRepository
                    .findActiveByExamTypeAndBodyPart(def.getExamType(), def.getBodyPart(), LocalDate.now());

            if (!existing.isEmpty()) {
                log.debug("[ProtocolSeeder] Protocollo già presente per {} + {}, skip.",
                        def.getExamType(), def.getBodyPart());
                skipped++;
                continue;
            }

            ExamProtocol protocol = new ExamProtocol();
            protocol.setExamType(def.getExamType());
            protocol.setBodyPart(def.getBodyPart());
            protocol.setDisplayName(def.getDisplayName());
            protocol.setSetupMinutes(def.getSetupMinutes());
            protocol.setExamMinutes(def.getExamMinutes());
            protocol.setTeardownMinutes(def.getTeardownMinutes());
            protocol.setRequiresContrast(def.isRequiresContrast());
            protocol.setRequiresFasting(def.isRequiresFasting());
            protocol.setEffectiveFrom(effectiveFrom);
            protocol.setNotes(def.getNotes());
            protocol.setUpdatedBy("system:seed");
            protocol.setActive(true);

            protocolRepository.save(protocol);
            log.info("[ProtocolSeeder] Protocollo creato: {} + {} ({}min totali)",
                    def.getExamType(), def.getBodyPart(),
                    def.getSetupMinutes() + def.getExamMinutes() + def.getTeardownMinutes());
            created++;
        }

        log.info("[ProtocolSeeder] Seed completato: {} creati, {} già presenti.", created, skipped);
    }
}
