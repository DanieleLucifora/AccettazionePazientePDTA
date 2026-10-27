package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiagnosticBookingDecisionRepository extends JpaRepository<DiagnosticBookingDecision, String> {
    @Override
    @EntityGraph(attributePaths = {"slot", "slot.resource", "slot.examProtocol"})
    Optional<DiagnosticBookingDecision> findById(String taskKey);
}
