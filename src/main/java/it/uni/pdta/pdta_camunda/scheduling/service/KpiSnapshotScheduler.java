package it.uni.pdta.pdta_camunda.scheduling.service;

import it.uni.pdta.pdta_camunda.scheduling.config.KpiSnapshotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KpiSnapshotScheduler {

    private final KpiSnapshotService kpiSnapshotService;
    private final KpiSnapshotProperties kpiSnapshotProperties;

    @Scheduled(cron = "#{@kpiSnapshotProperties.dailyCron}")
    public void runDailySnapshot() {
        if (!kpiSnapshotProperties.isEnabled()) {
            log.debug("[KpiSnapshotScheduler] Job disabilitato via config pdta.kpi.enabled=false");
            return;
        }

        kpiSnapshotService.generateYesterdaySnapshot();
    }
}
