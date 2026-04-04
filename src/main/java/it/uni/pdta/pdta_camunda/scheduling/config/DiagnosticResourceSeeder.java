package it.uni.pdta.pdta_camunda.scheduling.config;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceStatus;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Seed minimale delle risorse diagnostiche, utile per test locali CLI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiagnosticResourceSeeder implements ApplicationRunner {

    private final DiagnosticResourceRepository resourceRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createIfMissing("TAC-01", "TAC Sala 1", Set.of(ExamType.TAC, ExamType.BIOPSIA), "RADIOLOGIA_A");
        createIfMissing("RMN-01", "RMN Sala 1", Set.of(ExamType.RMN), "RADIOLOGIA_A");
        createIfMissing("PET-01", "PET-TC Sala 1", Set.of(ExamType.PET), "MED_NUCLEARE");
        createIfMissing("ECO-01", "Ecografia Ambulatorio 1", Set.of(ExamType.ECO, ExamType.ENDOSCOPIA), "AMBULATORI");
    }

    private void createIfMissing(String code, String name, Set<ExamType> supported, String siteId) {
        if (resourceRepository.findByCode(code).isPresent()) {
            return;
        }

        DiagnosticResource r = new DiagnosticResource();
        r.setCode(code);
        r.setName(name);
        r.setSupportedExamTypes(supported);
        r.setSiteId(siteId);
        r.setStatus(ResourceStatus.AVAILABLE);
        r.setDailyCapacityMinutes(600);
        resourceRepository.save(r);

        log.info("[DiagnosticResourceSeeder] Risorsa creata: {} ({})", code, name);
    }
}
