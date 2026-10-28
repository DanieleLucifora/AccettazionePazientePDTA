package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiagnosticBookingDecisionRepository extends JpaRepository<DiagnosticBookingDecision, String> {
    @Override
    @EntityGraph(attributePaths = {"slot", "slot.resource", "slot.examProtocol"})
    Optional<DiagnosticBookingDecision> findById(String taskKey);

    @EntityGraph(attributePaths = {"slot", "slot.resource", "slot.examProtocol"})
    java.util.List<DiagnosticBookingDecision> findByProcessInstanceKeyOrderByDecidedAtDesc(Long key);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"slot", "slot.resource", "slot.examProtocol"})
    @org.springframework.data.jpa.repository.Query("select d from DiagnosticBookingDecision d where d.taskKey = :key")
    Optional<DiagnosticBookingDecision> findLockedByTaskKey(@org.springframework.data.repository.query.Param("key") String key);
}
