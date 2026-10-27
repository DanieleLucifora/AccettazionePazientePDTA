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
*   **Web UI (primo incremento):** React + TypeScript, Vite e TanStack Query

## Architettura del Sistema
Il sistema è composto da:
*   **PdtaCamundaApplication** (Spring Boot): Applicazione principale che esegue i worker Camunda in background per processare automaticamente i job (firma documenti, generazione PDTA). Integra servizi di schedulazione (BookingService, ResourceEventService) e KPI (KpiSnapshotService).
*   **Servizi di Scheduling:** Gestione slot prenotabili, conflitti di prenotazione, audit eventi dettagliato e persistenza su H2.
*   **CLI Standalone**: Tre applicazioni per l'interazione utente. Specialista e diagnostica avviano un contesto Spring non web per accedere ai servizi di scheduling:
    *   `CaseManagerCli`: Interfaccia per inserimento pazienti e avvio processo
    *   `SpecialistCli`: Interfaccia unificata per Chirurghi e Oncologi (approva / rifiuta / richiedi esami con selezione slot)
    *   `DiagnosticCli`: Interfaccia laboratorio per gestione richieste esami, schedulazione (conferma/modifica/rifiuto slot) e invio referti
*   **Camunda REST Client**: Utilizzato dalle CLI per interagire con i processi e i task tramite API REST v2
*   **Registrazione condivisa**: `registration/PatientRegistrationService` e' usato dalla CLI Case Manager e dalle API web. Il client REST condiviso si trova in `workflow/`.
*   **Portale web locale**: API abilitate solo con profilo Spring `web-ui`, con frontend in `frontend/`. Camunda rimane responsabile del processo; il browser non lo chiama direttamente.

## Portale web — primo incremento

Disponibili:
* inserimento paziente con riepilogo di conferma e avvio del processo con le variabili BPMN esistenti;
* completamento separato del task di accesso, ripetibile senza avviare un nuovo processo;
* selettore dimostrativo Case Manager / Chirurgo / Oncologo;
* consultazione delle attivita' specialistiche, aggiornata ogni 5 secondi;
* errori espliciti e recupero della chiave di una registrazione pendente nella stessa sessione del browser.

**Non e' un login reale**: il selettore non implementa autorizzazioni. Il profilo web
ascolta solo su `127.0.0.1`; non esporlo in rete e usa esclusivamente dati fittizi.
Le API di registrazione usano l'identita' dimostrativa `CM001`, anche per l'assegnazione
del task iniziale (prima la CLI lo assegnava a `demo`).
Approvazioni, richieste esami, agenda e refertazione rimangono nelle CLI.
I dati paziente restano variabili Camunda: questo incremento non introduce un'anagrafica
o una lista persistente dei casi nel database applicativo.

### Sviluppo con frontend separato

Prerequisiti: Java 21, Node.js 22 LTS (almeno 22.12) o 24 LTS, Camunda 8.8
in esecuzione e processo BPMN/DMN gia' distribuito.
Su macOS, se Maven rileva Java 17 nonostante `java -version` indichi 21:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

Nel primo terminale, dalla radice:

```bash
SPRING_PROFILES_ACTIVE=web-ui ./start-app.sh
```

Nel secondo terminale:

```bash
cd frontend
npm ci
npm run dev
```

Apri `http://127.0.0.1:5173`. Il proxy Vite inoltra `/api` a Spring Boot sulla porta
8081; non occorre abilitare CORS. Non avviare una seconda applicazione worker sulla stessa porta.

Dopo l'avvio di un percorso la UI prova a completare il task iniziale. Se Camunda
non lo ha ancora indicizzato, usa **Verifica e completa accesso**. La chiave pendente
viene conservata in `sessionStorage` (nessun dato anagrafico), anche dopo un refresh.
Chiudere la sessione del browser puo' perdere questa scorciatoia: conserva la chiave
e verifica su Operate. Un errore durante la creazione puo' avere esito incerto:
non ripetere l'inserimento senza prima verificare l'eventuale processo gia' creato.

### Pacchetto unico

```bash
cd frontend
npm ci
npm run build
cd ..
./mvnw -Pweb-ui clean package
java -jar target/pdta-camunda-0.0.1-SNAPSHOT.jar --spring.profiles.active=web-ui
```

Apri `http://127.0.0.1:8081`. Il profilo **Maven** `web-ui` include `frontend/dist`
nel JAR; il profilo **Spring** omonimo abilita le API demo. Il build frontend va
eseguito prima del packaging. Senza il profilo Spring le API del portale non sono abilitate.

### API introdotte

| Metodo | Endpoint | Risultato |
|---|---|---|
| POST | `/api/registrations` | `201`, chiave del processo come stringa e stato `WAITING` |
| POST | `/api/registrations/{key}/complete-access` | `200`, `WAITING` oppure `COMPLETED` per la sola raccolta dati iniziale |
| GET | `/api/tasks?role=CHIRURGHI` | Task aperti del gruppo con dati paziente |
| GET | `/api/tasks?role=ONCOLOGI` | Task aperti del gruppo con dati paziente |

`COMPLETED` non significa paziente accettato: indica solo il completamento del task
di accesso. Input non validi restituiscono `400`; errori di integrazione sono esposti
come `502` con `ProblemDetail`, senza trasformarli in liste vuote o successi.

### Verifiche del primo incremento

```bash
./mvnw -Dtest=PatientRegistrationServiceTests,WorkflowClientTests,PortalControllerTests,PortalHttpTests test
cd frontend
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

I test HTTP avviano Spring su una porta casuale con H2 in memoria e integrazione
Camunda simulata. I test browser verificano il portale con risposte API simulate.
Non sostituiscono la verifica BPMN/DMN end-to-end con Camunda realmente attivo.
Anche il test di caricamento del contesto usa H2 in memoria, senza modificare il database locale.
Il cron KPI legge ora direttamente `pdta.kpi.daily-cron`: il precedente riferimento
SpEL a un bean con nome non registrato impediva l'avvio del contesto Spring.

### Verifica browser con Camunda reale

Con Camunda, BPMN/DMN e backend con profilo `web-ui` gia' attivi:

```bash
cd frontend
npm run test:live
```

Il test usa per default `http://127.0.0.1:8081` e Camunda su
`http://127.0.0.1:8080`; gli indirizzi possono essere impostati tramite
`PDTA_LIVE_URL` e `PDTA_CAMUNDA_URL`. Non avvia o arresta i servizi.
Crea un paziente fittizio `GUI-TEST-*`, verifica conferma, registrazione,
visibilita' in entrambi i gruppi, persistenza dopo refresh e ripetizione del
completamento accesso. Al termine cancella esclusivamente l'istanza creata
dal test: la traccia storica resta visibile in Camunda.

Il completamento accesso gestisce il ritardo dell'indice Camunda: se un comando
di assegnazione o completamento restituisce `404` per un task ancora indicizzato
come `CREATED`, il backend rilegge lo stesso task. Restituisce `COMPLETED` solo
se l'indice ne conferma il completamento, altrimenti `WAITING` per consentire
una nuova verifica senza ricreare il processo. Uno stato `CANCELED`, errori di
autorizzazione o indisponibilita' del servizio non sono trasformati in successi.
Il test live verifica sia il retry immediato sia quello dopo la propagazione.

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
