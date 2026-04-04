package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ResourceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ResourceEventRepository extends JpaRepository<ResourceEvent, Long> {

    List<ResourceEvent> findByResourceIdOrderByEventTsDesc(Long resourceId);

    List<ResourceEvent> findByCorrelationId(String correlationId);

    List<ResourceEvent> findByEventTsBetween(LocalDateTime from, LocalDateTime to);
}
