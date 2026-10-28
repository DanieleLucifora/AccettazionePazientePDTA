# Relazione dettagliata del progetto PDTA Camunda

> **Documento storico:** descrive anche l'architettura precedente con CLI.
> Le interfacce a terminale sono state sostituite dal portale web e rimosse
> dal codice corrente. Per avvio, funzionalita' e limiti attuali consultare
> il [README](../README.md). I dati e i servizi di scheduling restano disponibili.

## 1) Obiettivo dell'applicazione spiegato in modo semplice

L'applicazione serve ad aiutare un team clinico a gestire in modo ordinato e tracciabile la presa in carico di un paziente in un percorso PDTA.

In pratica, invece di coordinare attività complesse con telefonate, fogli separati o messaggi non strutturati, il sistema guida automaticamente gli attori coinvolti (Case Manager, Chirurgo, Oncologo, Diagnostica) nel giusto ordine, registra ogni decisione e rende visibile lo stato del caso in ogni momento.

L'obiettivo principale è ridurre errori organizzativi e tempi morti, mantenendo al tempo stesso una forte tracciabilità clinico-operativa.

## 2) Flusso operativo dell'applicazione

### 2.1 Inserimento e avvio del caso

1. Il Case Manager inserisce i dati iniziali del paziente.
2. Il sistema avvia il processo PDTA su Camunda.
3. Viene eseguita una prima valutazione automatica dei criteri di inclusione.

### 2.2 Valutazione specialistica parallela

1. Chirurgo e Oncologo ricevono i propri task.
2. Ogni specialista può:
- approvare,
- rifiutare,
- richiedere esami diagnostici.

### 2.3 Richiesta ed evasione degli esami

1. Se viene richiesto un esame, la richiesta passa alla Diagnostica.
2. La Diagnostica vede la proposta di esame, può confermare lo slot, modificarlo (con motivazione) oppure rifiutarlo (con motivazione).
3. A esame concluso, viene inserito il referto.
4. Il referto torna allo specialista richiedente e il processo prosegue.

### 2.4 Chiusura e finalizzazione

1. Completate le valutazioni, il workflow procede verso la finalizzazione.
2. I worker automatici generano il documento PDTA e simulano la firma.

## 3) Vantaggi pratici dell'utilizzo

### 3.1 Coordinamento migliore tra reparti

Ogni attore riceve il task corretto al momento corretto. Si riducono passaggi manuali e ambiguità.

### 3.2 Tracciabilità completa

Ogni evento importante viene registrato (richiesta, assegnazione slot, modifica, completamento esame, annullamento). Questo supporta audit, verifica qualità e analisi successive.

### 3.3 Scheduling robusto

La prenotazione degli slot è protetta da lock e controlli anti-sovrapposizione, riducendo conflitti e doppie prenotazioni.

### 3.4 Maggiore trasparenza gestionale

Con gli snapshot KPI giornalieri è possibile misurare volumi e andamento operativo in modo sistematico.

### 3.5 Maggiore continuità operativa

Con il database H2 su file condiviso, i dati restano disponibili tra processi diversi (app principale e CLI).

## 4) Modifiche principali introdotte nel branch

### 4.1 Evoluzione delle CLI operative

- SpecialistCli estesa per raccogliere tipo esame, distretto anatomico, preferenze di scheduling e scelta manuale di slot (calendario giorni + fasce).
- DiagnosticCli evoluta per gestire conferma/modifica/rifiuto con motivazioni obbligatorie in caso di modifica o rifiuto.
- Migliorata la propagazione delle variabili per ruolo (chirurgo/oncologo) e per slot selezionato.

### 4.2 Correzione polling task Camunda

- Il client REST Camunda è stato aggiornato con paginazione completa nella ricerca task.
- Questo risolve il problema dei task esistenti ma non visibili quando oltre la prima pagina.

### 4.3 Introduzione del motore di scheduling

- Inserite entità dominio scheduling: risorse diagnostiche, protocolli esame, slot prenotazione, eventi risorsa.
- Implementato BookingService con:
  - ricerca primo slot libero,
  - lista slot giornalieri,
  - prenotazione con lock pessimista,
  - retry in caso di conflitto,
  - controllo anti-overlap,
  - cancellazione con storicizzazione.

### 4.4 Introduzione audit eventi (Step D)

- Inserito ResourceEventService per scrittura centralizzata degli eventi.
- Eventi registrati: REQUEST_CREATED, SLOT_ASSIGNED, SLOT_RESCHEDULED, EXAM_COMPLETED, EXAM_CANCELED.
- Payload arricchito con metadati di audit:
  - requestedById,
  - requestedByName,
  - requestedByRole,
  - reportedById,
  - reportedByName,
  - reportedByRole.

### 4.5 Introduzione KPI snapshot giornaliero (Step G)

- Nuova tabella snapshot giornaliero KPI.
- Job schedulato giornalmente con cron configurabile.
- Calcolo KPI aggregati per giorno a partire da ResourceEvent:
  - richieste create,
  - slot assegnati,
  - slot ripianificati,
  - esami completati,
  - esami annullati,
  - numero risorse distinte,
  - numero casi distinti.

### 4.6 Aggiornamenti configurazione e processo

- application.yml esteso con:
  - configurazioni scheduling,
  - protocolli seed da YAML,
  - datasource H2 file-based condiviso,
  - configurazione job KPI.
- BPMN aggiornato per propagare correttamente variabili specialistiche e diagnostiche.

## 5) Architettura del sistema

## 5.1 Visione generale

Architettura a componenti con orchestrazione centralizzata:

- Camunda gestisce il flusso di processo e lo stato del workflow.
- Spring Boot ospita servizi business, persistenza e worker automatici.
- CLI separate forniscono interazione operativa per i diversi ruoli.

## 5.2 Componenti applicativi

### Applicazione principale (Spring Boot)

Responsabilità:
- avvio contesto applicativo,
- servizi scheduling e audit,
- worker automatici,
- persistenza dati.

### CLI Case Manager

Responsabilità:
- inserimento dati iniziali,
- avvio processo.

### CLI Specialist

Responsabilità:
- valutazione clinica,
- richiesta esami,
- eventuale selezione slot preferito.

### CLI Diagnostica

Responsabilità:
- presa in carico richiesta esami,
- conferma/modifica/rifiuto,
- inserimento referto,
- completamento task.

### Camunda REST Client

Responsabilità:
- ricerca task,
- assegnazione task,
- completamento task,
- lettura variabili.

## 5.3 Layer logici

- Layer di presentazione: CLI.
- Layer applicativo: service (BookingService, ResourceEventService, KpiSnapshotService).
- Layer dominio: entità e enum.
- Layer persistenza: repository JPA.
- Layer orchestrazione: BPMN Camunda.

## 6) Tecnologie principali utilizzate

- Java 21.
- Spring Boot 3.5.8.
- Spring Data JPA.
- Camunda Platform 8 con Camunda Spring Boot Starter 8.8.6.
- Camunda REST API v2.
- H2 database (file-based condiviso).
- Maven.
- Lombok.
- BPMN e DMN.

## 7) Concetti teorici e progettuali adottati

## 7.1 Orchestrazione a processi (BPM)

Il processo clinico è modellato esplicitamente in BPMN. Questo separa il flusso operativo dalla logica di basso livello e rende il processo più leggibile, verificabile e governabile.

## 7.2 Domain-driven structuring (approccio orientato al dominio)

Il dominio scheduling è stato modellato con entità specifiche (Resource, Protocol, Slot, Event), riducendo accoppiamento e ambiguità nel codice.

## 7.3 Concorrenza e consistenza

Per evitare conflitti nelle prenotazioni sono stati combinati:
- lock pessimistico sulla risorsa,
- verifica sovrapposizioni,
- retry controllato.

Questa combinazione aumenta l'affidabilità in presenza di operazioni simultanee.

## 7.4 Event logging e audit trail

Le transizioni operative vengono registrate in modo immutabile, così è possibile ricostruire la storia di ogni caso e produrre analisi oggettive.

## 7.5 Configurazione esterna e seed iniziale

Regole e protocolli non sono hardcoded: vengono letti da YAML. Questo rende il sistema più manutenibile e adattabile.

## 7.6 Batch analytics con snapshot

I KPI giornalieri sono salvati come snapshot. Concettualmente è un approccio da data mart operativo: query veloci e stabili su dataset aggregati, senza ricalcolare sempre tutto dallo storico eventi.

## 8) Modello dati funzionale (alto livello)

- ExamProtocol: definisce tempi e caratteristiche di un esame.
- DiagnosticResource: rappresenta una risorsa diagnostica disponibile.
- BookingSlot: rappresenta una prenotazione concreta in un intervallo temporale.
- ResourceEvent: registra eventi significativi sul ciclo di vita dell'esame.
- KpiDailySnapshot: conserva gli indicatori giornalieri aggregati.

## 9) Sicurezza operativa e affidabilità

- Difesa da doppia prenotazione tramite lock + anti-overlap.
- Tracciamento puntuale di modifiche e rifiuti con motivazione.
- Gestione task Camunda robusta con paginazione completa.
- Persistenza condivisa locale per evitare perdita di visibilità tra processi.

## 10) Osservabilità e misurazione

L'applicazione supporta due livelli di osservabilità:

- livello evento: cronologia dettagliata su resource_event,
- livello gestionale: KPI giornalieri su kpi_daily_snapshot.

Questo permette sia analisi operative fine-grain sia monitoraggio manageriale.

## 11) Limiti attuali e possibili evoluzioni

Limiti attuali:
- assenza di dashboard grafica dedicata (uso CLI + query DB),
- KPI focalizzati su metriche base (volumi principali),
- assenza di test end-to-end completi automatizzati su tutti i rami del flusso.

Evoluzioni consigliate:
- dashboard web per monitoraggio real-time,
- KPI avanzati (tempi medi tra fasi, SLA, saturazione per risorsa),
- endpoint API per export KPI,
- test di carico e test concorrenti dedicati allo scheduling,
- policy più avanzate di assegnazione slot (priorità clinica, urgenza, ottimizzazione capacità).

## 12) Conclusione

Il branch consolida il passaggio da una demo di workflow a una base applicativa molto più solida per la gestione PDTA: orchestration affidabile, scheduling consistente, audit completo e analytics giornaliera.

Dal punto di vista didattico e progettuale, il risultato mostra una integrazione coerente tra BPM (Camunda), business services (Spring), persistenza transazionale (JPA/H2) e osservabilità data-driven (eventi + snapshot KPI).
