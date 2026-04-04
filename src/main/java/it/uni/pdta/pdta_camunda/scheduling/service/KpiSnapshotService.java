package it.uni.pdta.pdta_camunda.scheduling.service;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.KpiDailySnapshot;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ResourceEvent;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.KpiDailySnapshotRepository;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ResourceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KpiSnapshotService {

    private final ResourceEventRepository resourceEventRepository;
    private final KpiDailySnapshotRepository kpiDailySnapshotRepository;

    @Transactional
    public KpiDailySnapshot generateSnapshot(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();

        List<ResourceEvent> events = resourceEventRepository.findByEventTsBetween(from, to);

        KpiDailySnapshot snapshot = kpiDailySnapshotRepository.findBySnapshotDate(day)
                .orElseGet(KpiDailySnapshot::new);

        snapshot.setSnapshotDate(day);
        snapshot.setRequestsCreated(countByType(events, ResourceEventType.REQUEST_CREATED));
        snapshot.setSlotsAssigned(countByType(events, ResourceEventType.SLOT_ASSIGNED));
        snapshot.setSlotsRescheduled(countByType(events, ResourceEventType.SLOT_RESCHEDULED));
        snapshot.setExamsCompleted(countByType(events, ResourceEventType.EXAM_COMPLETED));
        snapshot.setExamsCanceled(countByType(events, ResourceEventType.EXAM_CANCELED));
        snapshot.setDistinctResources(countDistinctResources(events));
        snapshot.setDistinctCases(countDistinctCases(events));
        snapshot.setGeneratedAt(LocalDateTime.now());

        KpiDailySnapshot saved = kpiDailySnapshotRepository.save(snapshot);

        log.info("[KpiSnapshotService] Snapshot {} salvato: requests={}, assigned={}, completed={}",
                day,
                saved.getRequestsCreated(),
                saved.getSlotsAssigned(),
                saved.getExamsCompleted());

        return saved;
    }

    @Transactional
    public KpiDailySnapshot generateYesterdaySnapshot() {
        return generateSnapshot(LocalDate.now().minusDays(1));
    }

    private int countByType(List<ResourceEvent> events, ResourceEventType type) {
        return (int) events.stream()
                .filter(e -> e.getEventType() == type)
                .count();
    }

    private int countDistinctResources(List<ResourceEvent> events) {
        Set<Long> resources = events.stream()
                .map(ResourceEvent::getResourceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        return resources.size();
    }

    private int countDistinctCases(List<ResourceEvent> events) {
        Set<String> cases = events.stream()
                .map(ResourceEvent::getCorrelationId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        return cases.size();
    }
}
