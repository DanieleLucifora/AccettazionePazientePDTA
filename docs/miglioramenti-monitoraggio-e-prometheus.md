# Miglioramenti Proposti all'Applicazione PDTA orientati a Monitoraggio e Massimizzazione Risorse

## 1) Premessa: obiettivo concettuale

L'obiettivo concettuale dell'applicazione è organizzare, monitorare e controllare il processo sanitario per sfruttare e massimizzare le risorse fisiche dell'azienda sanitaria.

In questo scenario, il monitoraggio ha un ruolo centrale perché consente di osservare in modo continuo:

- numero di pazienti attualmente presenti nel PDTA,
- tempistiche di esecuzione degli esami richiesti,
- numero e tipologia delle richieste effettuate,
- livelli di saturazione delle risorse disponibili.

Queste informazioni sono fondamentali per prendere decisioni di capacità (posti letto, slot diagnostici, allocazione personale/reparti) in un contesto di risorse limitate.

## 2) Direzione di evoluzione dell'applicazione

## 2.1 Da monitoraggio descrittivo a monitoraggio decisionale

Evoluzione consigliata:

- passare da metriche puramente descrittive (quanti task, quanti eventi) a metriche decisionali,
- introdurre indicatori utili ad anticipare i colli di bottiglia,
- supportare decisioni operative quotidiane.

Esempi:

- non solo quanti task pendenti, ma quanti pazienti sono a rischio di ritardo oltre soglia clinica,
- non solo quanti slot disponibili, ma quali slot aggiungere o riallocare per ridurre il backlog.

## 2.2 Vista unica Domanda vs Capacità

L'applicazione dovrebbe offrire una vista unica che confronti:

- Domanda:
  - pazienti attivi nel PDTA,
  - nuove richieste per tipo esame,
  - urgenze e priorità cliniche.

- Capacità:
  - slot disponibili per risorsa,
  - saturazione giornaliera,
  - tasso di cancellazioni/no-show.

- Gap operativo:
  - backlog per tipologia esame,
  - tempo stimato di smaltimento,
  - rischio di violazione SLA clinica.

## 2.3 Estensione del dominio risorse fisiche

Per massimizzare davvero le risorse aziendali, il modello dovrebbe includere progressivamente:

- disponibilità posti letto,
- vincoli di degenza,
- dipendenze tra diagnostica e ricovero/dimissione.

Obiettivo:

- evitare che il collo di bottiglia diagnostico blocchi ricoveri o dimissioni,
- armonizzare pianificazione diagnostica e pianificazione posti letto.

## 2.4 Pianificazione predittiva

Usando lo storico (resource_event + kpi_daily_snapshot), introdurre stime a breve termine:

- domanda prevista a 7 giorni per tipologia esame,
- lead time atteso (media e percentili),
- capacità minima necessaria per rispettare SLA.

Beneficio:

- pianificazione proattiva e non reattiva,
- riduzione dei picchi non gestiti.

## 2.5 Regole operative automatiche

Inserire policy automatiche configurabili, ad esempio:

- se backlog TAC Torace supera soglia -> incremento slot TAC il giorno successivo,
- se referti in ritardo oltre soglia -> alert al coordinamento,
- se saturazione risorsa supera 85-90% per periodo continuo -> redistribuzione proposte slot.

## 3) Uso di Prometheus: strategia operativa

Prometheus va utilizzato non solo per metriche tecniche ma soprattutto per metriche business-cliniche.

## 3.1 Metriche business da esporre

Set iniziale consigliato:

- pdta_active_patients (gauge)
- pdta_exam_requests_total{tipo_esame, distretto, richiedente_ruolo} (counter)
- pdta_exam_completed_total{tipo_esame} (counter)
- pdta_exam_rejected_total{motivo} (counter)
- pdta_exam_lead_time_seconds (histogram, richiesta -> completamento)
- pdta_exam_report_time_seconds (histogram, esecuzione -> referto)
- pdta_slot_utilization_ratio{risorsa} (gauge)
- pdta_slot_backlog{tipo_esame} (gauge)
- pdta_no_show_total (counter)
- pdta_cancellation_total (counter)

## 3.2 Metriche tecniche da mantenere

- latenza chiamate REST Camunda,
- tasso errori task assignment/completion,
- throughput elaborazione task,
- stato JVM (memoria, GC, thread),
- pool connessioni DB.

## 3.3 Integrazione pratica nel progetto

Passi consigliati:

1. aggiungere Micrometer Prometheus Registry nelle dipendenze Maven,
2. esporre endpoint /actuator/prometheus,
3. configurare esposizione endpoint in application.yml,
4. strumentare i punti core:
   - BookingService,
   - ResourceEventService,
   - KpiSnapshotService,
   - eventuali service di orchestrazione task.

5. configurare Prometheus con scraping periodico (es. ogni 15-30 secondi),
6. collegare Grafana per dashboard operative e direzionali.

## 3.4 Alerting proattivo

Alert minimi consigliati:

- lead time p95 oltre soglia clinica per tipo esame,
- backlog in crescita continua oltre finestra temporale,
- saturazione risorsa > 90% per periodo prolungato,
- riduzione anomala completamenti rispetto baseline,
- aumento errori su interazione Camunda.

## 3.5 Dashboard prioritarie

### Dashboard Operativa (tempo reale)

- pazienti attivi,
- richieste pendenti,
- completamenti orari/giornalieri,
- saturazione per risorsa.

### Dashboard Direzionale (trend)

- trend 7/30 giorni,
- SLA rispettate/non rispettate,
- confronto capacità richiesta vs capacità disponibile.

### Dashboard Collo di Bottiglia

- risorse più critiche,
- tipologie esame più critiche,
- tempi di attesa per distretto.

## 4) Perché Prometheus è adatto a questo contesto

Prometheus abilita:

- serie temporali ad alta frequenza (dinamica reale del sistema),
- alerting tempestivo prima dell'impatto clinico-operativo,
- misurazione oggettiva delle decisioni di capacità.

In sintesi, trasforma il workflow PDTA da semplice automazione di processo a piattaforma di governo operativo.

## 5) Roadmap consigliata di adozione

## Fase 1 (immediata)

- strumentazione metriche base business,
- endpoint Prometheus attivo,
- prime dashboard operative.

## Fase 2 (breve termine)

- alerting su backlog, lead time e saturazione,
- definizione soglie condivise con i referenti clinici.

## Fase 3 (medio termine)

- metriche predittive,
- policy automatiche di riallocazione capacità,
- integrazione con decisioni su posti letto e percorso ricovero/dimissione.

## 6) Conclusione

La direzione evolutiva consigliata è usare il monitoraggio non come report finale, ma come meccanismo continuo di controllo e ottimizzazione.

Con Prometheus (e dashboard/alert ben progettati), l'applicazione può diventare uno strumento concreto per:

- migliorare i tempi di risposta,
- ridurre i colli di bottiglia,
- massimizzare l'uso delle risorse fisiche,
- supportare decisioni organizzative basate su dati.
