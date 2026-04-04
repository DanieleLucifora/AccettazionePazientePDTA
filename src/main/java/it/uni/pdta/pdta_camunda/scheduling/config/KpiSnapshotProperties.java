package it.uni.pdta.pdta_camunda.scheduling.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurazione scheduler KPI giornaliero.
 */
@ConfigurationProperties(prefix = "pdta.kpi")
@Getter
@Setter
public class KpiSnapshotProperties {

    /** Abilita/disabilita il job schedulato. */
    private boolean enabled = true;

    /** Cron per la generazione snapshot (default: ogni giorno alle 00:10). */
    private String dailyCron = "0 10 0 * * *";
}
