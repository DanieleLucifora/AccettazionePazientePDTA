package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ExamProtocolRepository extends JpaRepository<ExamProtocol, Long> {

    /**
     * Restituisce i protocolli attivi per tipo esame + parte corporea
     * la cui effectiveFrom è <= oggi, ordinati dalla versione più recente.
     * Il primo elemento è il protocollo correntemente in vigore.
     */
    @Query("""
        SELECT p FROM ExamProtocol p
        WHERE p.examType = :examType
          AND p.bodyPart = :bodyPart
          AND p.active = true
          AND p.effectiveFrom <= :today
        ORDER BY p.effectiveFrom DESC
        """)
    List<ExamProtocol> findActiveByExamTypeAndBodyPart(
        @Param("examType") ExamType examType,
        @Param("bodyPart") BodyPart bodyPart,
        @Param("today") LocalDate today
    );
}
