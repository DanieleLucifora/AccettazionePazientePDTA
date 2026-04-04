package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.KpiDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface KpiDailySnapshotRepository extends JpaRepository<KpiDailySnapshot, Long> {

    Optional<KpiDailySnapshot> findBySnapshotDate(LocalDate snapshotDate);
}
