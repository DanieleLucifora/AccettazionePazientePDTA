package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiagnosticResourceRepository extends JpaRepository<DiagnosticResource, Long> {

    /**
     * Acquisisce un lock pessimistico (SELECT … FOR UPDATE) sulla risorsa.
     * Da usare prima di verificare disponibilità e creare uno slot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DiagnosticResource r WHERE r.id = :id")
    Optional<DiagnosticResource> findByIdWithLock(@Param("id") Long id);

    Optional<DiagnosticResource> findByCode(String code);

    List<DiagnosticResource> findByStatus(ResourceStatus status);
}
