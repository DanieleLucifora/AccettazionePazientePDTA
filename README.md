# Workflow accettazione paziente PDTA

![Diagramma BPMN](/presa_in_carico_pdta.png)

## Descrizione del Progetto
Il progetto implementa un workflow dimostrativo focalizzato sulla fase di accettazione di un paziente all'interno di un Percorso Diagnostico Terapeutico Assistenziale (PDTA). Il sistema automatizza i passaggi preliminari di verifica e validazione necessari per la presa in carico e introduce **schedulazione reale degli esami con tabella oraria**.

Il flusso operativo prevede:
1.  **Registrazione:** Inserimento dati paziente tramite interfaccia Case Manager.
2.  **Valutazione Automatica:** Verifica dei criteri di inclusione tramite regole DMN.
3.  **Valutazione Specialistica:** Workflow parallelo di validazione da parte di Chirurgo e Oncologo con tre esiti possibili: approvazione, rifiuto o richiesta di approfondimenti diagnostici.
4.  **Esami Diagnostici:** Se richiesti da uno o entrambi gli specialisti, il laboratorio riceve i task, gestisce la schedulazione con slot temporali concreti (conferma, modifica o rifiuto della prenotazione), e invia i referti che tornano allo specialista richiedente. Ogni esame ha durata standardizzata, risorse assegnate e disponibilità gestita a calendario.
5.  **Tracciabilità:** Ogni evento (richiesta, assegnazione slot, modifica, completamento) registra chi richiede l'esame (specialista) e chi invia il referto (diagnostica).
6.  **Finalizzazione:** Generazione automatica del documento PDTA con apposizione di firme digitali simulate.

## Tecnologie Utilizzate
*   **Linguaggio:** Java 21
*   **Framework:** Spring Boot 3.5.8 (solo per applicazione principale)
*   **Orchestrator:** Camunda Platform 8 (Zeebe Engine)
*   **Persistenza:** Spring Data JPA + H2 Database (embedded file-based)
*   **Build Tool:** Maven

## Architettura del Sistema
Il sistema è composto da:
*   **PdtaCamundaApplication** (Spring Boot): Applicazione principale che esegue i worker Camunda in background per processare automaticamente i job (firma documenti, generazione PDTA). Integra servizi di schedulazione (BookingService, ResourceEventService) e KPI (KpiSnapshotService).
*   **Servizi di Scheduling:** Gestione slot prenotabili, conflitti di prenotazione, audit eventi dettagliato e persistenza su H2.
*   **CLI Standalone** : Tre applicazioni Java pure senza Spring Boot per l'interazione utente:
    *   `CaseManagerCli`: Interfaccia per inserimento pazienti e avvio processo
    *   `SpecialistCli`: Interfaccia unificata per Chirurghi e Oncologi (approva / rifiuta / richiedi esami con selezione slot)
    *   `DiagnosticCli`: Interfaccia laboratorio per gestione richieste esami, schedulazione (conferma/modifica/rifiuto slot) e invio referti
*   **Camunda REST Client**: Utilizzato dalle CLI per interagire con i processi e i task tramite API REST v2

## Prerequisiti
Per eseguire l'applicazione è necessario disporre di:
*   Java Development Kit (JDK) 21 o superiore
*   Camunda 8 Run (C8Run)
*   Camunda Modeler (per visualizzare o modificare i diagrammi)
*   Maven (opzionale, se si utilizza il wrapper mvnw incluso)

## Istruzioni per l'Esecuzione

### 1. Avvio dell'Infrastruttura
Avviare Camunda 8 Run (assicurarsi che le porte 26500, 8080 e 8081 siano libere).

### 2. Compilazione
Compilare il progetto ed eseguire i test unitari:
```bash
./mvnw clean install
```

### 3. Esecuzione dei Componenti
Il sistema è composto da diverse applicazioni che devono essere avviate in finestre di terminale separate, nel seguente ordine:

**Terminale 1: Applicazione Principale (Worker Camunda)**
```bash
./start-app.sh
```
Avvia l'applicazione Spring Boot con i worker Camunda che processano automaticamente i job in background.

**Terminale 2: Interfaccia Case Manager (Avvio Processi)**
```bash
./start-casemanager.sh
```
CLI Java standalone per l'inserimento di nuovi pazienti nel sistema PDTA.

**Terminale 3: Interfaccia Specialista (Chirurgo)**
```bash
./start-specialist.sh
```
Selezionare opzione 1 per simulare un Chirurgo.

**Terminale 4: Interfaccia Specialista (Oncologo)**
```bash
./start-specialist.sh
```
Selezionare opzione 2 per simulare un Oncologo.

**Terminale 5: Interfaccia Diagnostica (Laboratorio)**
```bash
./start-diagnostic.sh
```
Avvia la CLI di laboratorio che monitora e completa i task "Esami Diagnostici".

## Note Funzionali
*   La CLI specialista invia variabili di processo per la gestione degli esami: `requiresExam`, `examType`, `requesterRole`, `requesterName`. Può selezionare manualmente slot prenotabili dall'interfaccia.
*   La CLI diagnostica può: (1) confermare lo slot proposto, (2) modificare data/ora con giustificazione, (3) rifiutare con motivazione. Ogni azione è tracciata negli audit eventi.
*   I referti sono gestiti per ruolo (`chirurgoExamResult`, `oncologoExamResult`) così ogni specialista visualizza il referto corretto.
*   La CLI diagnostica gestisce eventuali task stale con deduplica chiavi e fallback su errori 404 durante assegnazione/completamento.
*   **Configurazione dati:** Attualmente esami, risorse diagnostiche e slot temporali sono hardcoded nel seeder per velocità di prototipazione. In futuro saranno gestibili dinamicamente tramite CLI.
*   **KPI Giornalieri:** Sistema automatico di snapshot KPI schedulato giornalmente (configurabile via `application.yml`).
*   **Tracciabilità completa:** Ogni evento di scheduling registra chi ha richiesto e chi ha eseguito/refertato, garantendo visibilità operativa totale.
