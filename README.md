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
*   **Web UI:** React + TypeScript, Vite e TanStack Query

## Architettura del Sistema
Il sistema è composto da:
*   **PdtaCamundaApplication** (Spring Boot): Applicazione principale che esegue i worker Camunda in background per processare automaticamente i job (firma documenti, generazione PDTA). Integra servizi di schedulazione (BookingService, ResourceEventService) e KPI (KpiSnapshotService).
*   **Servizi di Scheduling:** Gestione slot prenotabili, conflitti di prenotazione, audit eventi dettagliato e persistenza su H2.
*   **Servizi applicativi**: Registrazione, valutazioni, richieste esami, prenotazioni e refertazione sono esposti dalle API del portale, mantenendo logica e persistenza nel backend.
*   **Camunda REST Client**: Il client in `workflow/` integra le API REST v2 di Camunda per processi, task e variabili.
*   **Portale web locale**: API abilitate solo con profilo Spring `web-ui`, con frontend in `frontend/`. Camunda rimane responsabile del processo; il browser non lo chiama direttamente.

Il portale e' l'interfaccia operativa applicativa per Case Manager, Chirurgo,
Oncologo e Diagnostica. 
Per avvio e uso attuali fare riferimento a questo README.

## Portale web locale

Disponibili:
* accesso con ID paziente, nome, cognome e data di nascita obbligatori,
  selezione dei codici diagnosi DMN e riepilogo prima dell'avvio;
* area Case Manager **Percorsi paziente** con ricerca per ID esatto, filtro di stato,
  paginazione e dettaglio storico di attivita', prenotazioni e referti;
* completamento separato del task di accesso, ripetibile senza avviare un nuovo processo;
* selettore dimostrativo Case Manager / Chirurgo / Oncologo / Diagnostica;
* consultazione delle attivita' specialistiche, aggiornata ogni 5 secondi;
* dettaglio attivita' con dati paziente, assegnazione, cartella clinica,
  referto e note pertinenti al gruppo selezionato;
* presa in carico senza sovrascrivere l'assegnazione ad altri operatori;
* approvazione o rifiuto motivato con riepilogo e conferma esplicita dell'esito;
* richiesta di esami con protocolli attivi, primo slot automatico oppure calendario
  manuale dei prossimi 10 giorni, riepilogo e conferma;
* richieste diagnostiche con dettaglio del richiedente, presa in carico,
  conferma della proposta, modifica motivata della fascia oppure rifiuto motivato;
* decisioni diagnostiche persistenti, con protezione dalle doppie prenotazioni
  dello stesso task;
* referto diagnostico obbligatorio con riepilogo, conferma, salvataggio persistente
  e ritorno al solo specialista richiedente, dopo la fine della fascia prenotata;
* errori espliciti e recupero della chiave di una registrazione pendente nella stessa sessione del browser.

**Non e' un login reale**: il selettore non implementa autorizzazioni. Il profilo web
ascolta solo su `127.0.0.1`; non esposto in rete e usa esclusivamente dati fittizi.
Le API di registrazione usano l'identita' dimostrativa `CM001`, anche per l'assegnazione
del task iniziale.
Gli specialisti simulati sono `CH001` (chirurgo) e `ON001` (oncologo), scelti dal
backend per il ruolo selezionato, non dal corpo della richiesta.
L'operatore Diagnostica simulato e' `LAB001` (Mario Neri).
Prenotazione definitiva e refertazione sono disponibili nella GUI.
I dati paziente restano variabili Camunda: la lista dei percorsi interroga gli indici
del motore, senza introdurre una seconda anagrafica nel database applicativo.

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

Apri `http://127.0.0.1:5173`. Il proxy Vite inoltra `/api` a Spring Boot sulla porta 8081.

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
| GET | `/api/registrations/options` | Codici diagnosi letti dalla DMN e data corrente del server |
| GET | `/api/pathways?patientId=RSSMRA80A01H501U&state=ACTIVE&offset=0` | Fino a 20 percorsi, totale e `hasNext`; filtri opzionali, stato `ACTIVE` / `COMPLETED` / `TERMINATED` |
| GET | `/api/pathways/{key}` | Anagrafica disponibile, stato motore, eventi, attivita', decisioni diagnostiche, prenotazioni storiche e referti |
| POST | `/api/registrations/{key}/complete-access` | `200`, `WAITING` oppure `COMPLETED` per la sola raccolta dati iniziale |
| GET | `/api/tasks?role=CHIRURGHI` | Task aperti del gruppo con dati paziente |
| GET | `/api/tasks?role=ONCOLOGI` | Task aperti del gruppo con dati paziente |
| GET | `/api/tasks/{key}?role=CHIRURGHI` | Dettaglio del task aperto del gruppo (anche `ONCOLOGI`) |
| POST | `/api/tasks/{key}/claim?role=CHIRURGHI` | `200`, `CLAIMED`, assegnazione confermata dal broker (anche `ONCOLOGI`) |
| POST | `/api/tasks/{key}/decision?role=CHIRURGHI` | `200`, `COMPLETED`, task completato dal broker (anche `ONCOLOGI`) |
| GET | `/api/exams/options` | Combinazioni tipo esame / parte corporea con protocollo attivo e intervallo date del calendario |
| GET | `/api/exams/slots?examType=TAC&bodyPart=TORACE&day=2026-10-28` | Fasce disponibili del giorno (lista vuota se nessuna); data compresa nei prossimi 10 giorni |
| POST | `/api/tasks/{key}/exam-request?role=CHIRURGHI` | `200`, `COMPLETED`, richiesta esame inviata completando la valutazione (anche `ONCOLOGI`) |
| GET | `/api/diagnostics/tasks` | Richieste aperte del laboratorio e decisioni salvate |
| GET | `/api/diagnostics/tasks/{key}` | Dettaglio richiesta, proposta corrente e prenotazione persistita |
| POST | `/api/diagnostics/tasks/{key}/claim` | `200`, `CLAIMED`, assegnazione non sovrascrivibile a `LAB001` |
| POST | `/api/diagnostics/tasks/{key}/booking` | `200`, `BOOKED` oppure `COMPLETED` per il rifiuto restituito a Camunda |
| POST | `/api/diagnostics/tasks/{key}/report` | `200`, referto persistito e stato `SENT` dopo conferma Camunda |
| GET | `/api/diagnostics/tasks/{key}/report` | `200`, `{"report":null}` se assente, oppure testo, date e stato `PENDING` / `SENT`, anche a task chiuso |

### Qualita' dei dati di accesso

La GUI richiede codice fiscale, nome, cognome, data di nascita e codice diagnosi.
L'identificativo del paziente e' il suo **codice fiscale** (16 caratteri, maiuscolo
normalizzato): il server verifica formato, omocodia e carattere di controllo. La variabile
Camunda resta `patientID`, per compatibilita' con BPMN e worker. Non e' un vincolo di
unicita': un paziente puo' avere piu' percorsi; verificare quelli esistenti prima
dell'inserimento. La ricerca percorsi e' esatta e trova anche i percorsi precedenti con ID
non fiscale. Il codice fiscale e' un dato personale: nelle demo usare solo codici fittizi.
Nome e cognome sono obbligatori (massimo 100 caratteri ciascuno), devono contenere
lettere e supportano accenti, apostrofi, trattini e nomi composti. Gli spazi vengono
normalizzati; la data deve essere valida, con anno positivo e non futura rispetto al server.

Il selettore diagnosi usa le sei regole della DMN nel pacchetto (`1001`, `2609`,
`3202`, `5201`, `6401`, `7001`), senza inventare descrizioni cliniche o modificarne
i criteri di inclusione. La DMN distribuita in Camunda deve coincidere con quella
nel pacchetto applicativo. Un errore di caricamento del catalogo blocca l'inserimento.
L'URL della cartella resta facoltativo: solo URL assoluti HTTP/HTTPS con host e senza
credenziali incorporate. Non si aggiungono motivo di accesso o note.

Il nuovo corpo di `POST /api/registrations` e':

```json
{
  "patientID": "RSSMRA80A01H501U",
  "firstName": "Paziente",
  "lastName": "Fittizio",
  "birthDate": "1980-01-02",
  "diagnosiCode": "6401",
  "clinicalFileUrl": ""
}
```

Il backend ripete le validazioni sulle richieste API. Il vecchio corpo con solo
`patientData` non e' piu' valido per **nuovi inserimenti**. Per compatibilita' con
specialisti e worker vengono ancora scritti `patientID`, `patientData` (nome e
cognome), `diagnosiCode` e `caseManagerId`; si aggiungono `patientFirstName`,
`patientLastName`, `patientBirthDate` e `registrationSchemaVersion: 2`.
Non occorrono migrazioni dei dati o una nuova distribuzione BPMN/DMN.
Il modulo nativo Tasklist rimane quello legacy: i controlli descritti si applicano
agli ingressi GUI/API, non a modifiche dirette delle variabili da altri client.

### Percorsi paziente

Nella vista **Case Manager**, scegli **Percorsi paziente** nel menu (disponibile
anche su mobile). Cerca un ID esatto, inclusi gli zeri iniziali, oppure consulta
tutti i percorsi; filtra per stato e scorri pagine di 20 risultati, dal piu' recente.
Apri il dettaglio per vedere anagrafica, diagnosi, versione BPMN, date,
stato tecnico e incidenti, attivita' con assegnazioni, eventi, decisioni diagnostiche,
prenotazioni ed eventuali referti. Non sono presenti comandi di modifica.

Sono inclusi i percorsi precedenti ancora conservati in Camunda: i campi
anagrafici non strutturati restano nel testo originale; nome, cognome e data di
nascita mancanti non vengono ricostruiti. Le prenotazioni CLI senza decisione
collegata al task sono mostrate separatamente, senza attribuire loro un referto
per supposizione. Gli esiti piu' vecchi possono esistere solo nelle variabili
`chirurgoExamResult` / `oncologoExamResult`, mostrate come ultimo esito del ruolo:
non costituiscono uno storico completo di tutti i referti precedenti.

Lo stato `COMPLETED` del motore non certifica l'accettazione clinica.
La GUI distingue l'evento di conclusione PDTA dall'evento di esclusione raggiunto
in un ramo; quest'ultimo non chiude necessariamente gli altri rami paralleli.
I flag specialistici sono l'ultima fotografia delle variabili e possono anche
derivare da una richiesta di esame: un flag negativo da solo non e' un rifiuto.
I referti salvati ma senza conferma di invio restano `PENDING`, senza riconciliazioni
o comandi automatici in questa vista di sola lettura.

Gli indici Camunda sono asincroni: dopo un'azione usa **Aggiorna percorsi** o
**Aggiorna dettaglio percorso**. Le letture di motore e database non costituiscono
una fotografia transazionale comune. Gli errori sono espliciti e un aggiornamento
fallito nasconde i dati precedenti; un percorso non trovato restituisce `404`,
non un falso stato completato. Lo storico e' limitato dalla conservazione dei
dati Camunda e dal database di scheduling condiviso utilizzato.

### Prenotazione diagnostica

Seleziona **Diagnostica**, apri una richiesta e usa **Prendi in carico richiesta**.
Solo dopo la conferma dell'assegnazione puoi rivedere e confermare la decisione:

* **Conferma proposta**: prenota la fascia richiesta dallo specialista, se ancora
  disponibile; per la modalita' automatica cerca nei prossimi 10 giorni.
* **Modifica data o fascia**: scegli una disponibilita' del calendario dei prossimi
  10 giorni e inserisci una motivazione (massimo 2000 caratteri).
* **Rifiuta richiesta**: salva la motivazione e completa subito il task diagnostico,
  restituendo una notifica di rifiuto allo specialista, non un referto clinico.

Conferma e modifica occupano realmente la risorsa. Il task Camunda resta aperto per
la refertazione dalla GUI, riutilizzando la prenotazione senza crearne un'altra.
Il database di scheduling conserva decisioni, prenotazioni e referti anche dopo
la rimozione delle CLI.
La GUI non riprenota una decisione gia' salvata, nemmeno se lo slot viene successivamente
annullato: verifica lo stato mostrato e risolvi il caso prima di refertare.

Decisione, slot ed eventi di prenotazione condividono una transazione nel database;
la chiave univoca del task impedisce decisioni duplicate. Camunda e database **non**
condividono una transazione distribuita: dopo un errore o timeout verifica prima
lo stato, anche su Operate. Un rifiuto gia' persistito ma non ancora restituito puo'
essere ripreso con **Restituisci rifiuto salvato**, senza cambiare la motivazione.
Un task riassegnato o chiuso da altri client fra controllo broker e salvataggio richiede
riconciliazione manuale. Le prenotazioni storiche create dalla vecchia CLI, prima
di questo incremento, non hanno una decisione associata al task: verificarle prima
di usare i nuovi comandi su richieste pregresse.

Il BPMN aggiornato separa gli esami del chirurgo da quelli dell'oncologo e li
riporta direttamente al rispettivo specialista, senza instradamento basato su
una variabile globale condivisa. Distribuiscilo prima di avviare nuovi percorsi
con richieste parallele. I processi gia' avviati mantengono la vecchia definizione:
la GUI accetta il vecchio task condiviso solo se la richiesta e' univoca; se entrambi
gli specialisti hanno richieste pendenti ne impedisce la lavorazione.

### Refertazione diagnostica

Nel dettaglio di una richiesta prenotata, il riquadro **Referto diagnostico** si
abilita **solo dopo la fine della fascia prenotata**, secondo l'orologio locale del
server. Non basta cambiare l'orologio del browser: il backend ripete il controllo.
La prenotazione deve essere attiva (`BOOKED` / `IN_PROGRESS`), il task aperto e
assegnato a `LAB001`, con contesto esame e richiedente coerenti.

Inserisci un testo non vuoto di massimo 10000 caratteri, usa **Rivedi referto** e
poi **Conferma e invia referto**. La bozza resta solo in memoria nel componente:
uscire dal dettaglio o ricaricare la pagina prima della conferma puo' perderla.
Il riepilogo non invia comandi. Non viene piu' generato un esito clinico automatico
se il testo e' vuoto.

Alla conferma, il testo diventa definitivo. Il salvataggio del referto, il passaggio
dello slot a `COMPLETED` e l'unico evento `EXAM_COMPLETED` avvengono nella stessa
transazione database. Un esame completato non puo' essere annullato tramite il
servizio di prenotazione. Successivamente viene completato il task Camunda con
`chirurgoExamResult` oppure `oncologoExamResult`, metadati dello slot e del
refertatore, flag della sola richiesta e chiave di correlazione per ruolo.
Il BPMN versione 7 gia' distribuito riporta al richiedente corretto: non occorre
una nuova distribuzione del processo per questo incremento.

Database e Camunda non condividono una transazione distribuita. Se la risposta
non arriva, **non riscrivere il referto**: usa **Verifica stato referto**.
Un referto `PENDING` e' salvato, ma l'invio non e' ancora confermato; se il task
e' ancora aperto puoi usare **Riprendi invio referto salvato**. Viene inviato
esattamente lo stesso testo, senza un nuovo evento di completamento.
Il recupero e' disponibile anche dopo refresh, tramite lo stesso archivio
persistente e servizio di invio. Non e' prevista la rettifica di referti gia' confermati.

Se il dettaglio del task e' ormai chiuso, il pulsante di verifica del referto resta
disponibile nel messaggio di errore. La verifica puo' riconciliare la conferma persa
solo se Camunda mostra il task `COMPLETED` con testo e chiave di correlazione
corrispondenti. Non ripete comandi Camunda. Uno stato chiuso senza questa evidenza,
un task annullato, o un errore di lettura non vengono trasformati in un invio riuscito:
verifica su Operate. I riepiloghi gia' confermati restano disponibili tramite l'API
del referto usando la chiave del task e nel dettaglio **Percorsi paziente**.

Il pulsante **Apri dettaglio** mostra una scheda dedicata, aggiornata ogni 5 secondi,
con ritorno alla lista. La sola apertura del dettaglio non assegna o completa il task.
Il backend verifica chiave e gruppo prima di caricare le variabili: restituisce
`404` se il task non e' visibile nel gruppo e `409` se non e' piu' aperto.
Se una verifica fallisce, i precedenti dati del dettaglio vengono nascosti.
Questa selezione per gruppo non sostituisce le autorizzazioni di un login reale.

Il referto usa `chirurgoExamResult` o `oncologoExamResult`; il vecchio `examResult`
e' usato solo se `requesterRole` identifica il gruppo corrente, per non mostrare
il referto dell'altro specialista. Le note usano `chirurgoNote` o `oncologoNote`.
Solo indirizzi assoluti HTTP/HTTPS senza credenziali diventano link alla cartella
clinica, aperti in una nuova scheda; indirizzi non utilizzabili vengono omessi
con un avviso nei log, senza riportare l'URL.

### Presa in carico e valutazione

Usa **Prendi in carico** e attendi che l'assegnazione sia visibile nel dettaglio.
Solo il task assegnato all'operatore corrente consente l'invio dell'esito; quello
assegnato ad altri rimane consultabile senza comandi. L'assegnazione usa
`allowOverride: false`, anche prima del completamento per ricontrollarla sul broker.
Questi comandi separati non costituiscono una transazione atomica con eventuali
riassegnazioni eseguite da altri client.

Il corpo della decisione e' `{"outcome":"APPROVE","note":"..."}` oppure
`{"outcome":"REJECT","note":"Motivazione obbligatoria"}`. Le note hanno limite
di 2000 caratteri; spazi iniziali/finali vengono rimossi. **Rivedi esito** non invia
comandi: il task viene completato solo con **Conferma e invia esito**.
Il servizio di valutazione costruisce il payload BPMN
(`isApproved`, `requiresExam: false`, `note`, `validatorId`, `validatorRole`).

I comandi verificano anche l'elemento BPMN previsto per il ruolo. Task chiusi,
assegnazioni incompatibili o comandi rifiutati per stato non attuale restituiscono
`409`. Non viene ritentato automaticamente un comando dall'esito incerto: verifica
lo stato prima di un nuovo invio. Un completamento ripetuto e' un conflitto, non
un nuovo successo. Camunda indicizza in modo asincrono: lista e assegnazione possono
aggiornarsi qualche secondo dopo la conferma del comando.

`COMPLETED` non significa paziente accettato: indica solo il completamento del task
di accesso o della singola valutazione. Il BPMN esistente mantiene parallele le due
valutazioni: un rifiuto raggiunge **Esclusione Paziente**, ma non termina
automaticamente l'altro ramo. Questo incremento non cambia tale comportamento.
Firme e generazione documento sono simulate: non viene creato un PDF scaricabile.
Input non validi restituiscono `400`; errori di integrazione sono esposti
come `502` con `ProblemDetail`, senza trasformarli in liste vuote o successi.

### Richiesta esami diagnostici

Dopo la presa in carico scegli **Richiedi esami diagnostici** nel campo **Esito**.
Il catalogo propone solo combinazioni con protocollo attivo e gia' in vigore:
non tutte le combinazioni degli enum hanno un protocollo configurato.
Scegli tipo esame, parte corporea e modalita':

* **Primo slot libero (automatico)**: il laboratorio gestisce la proposta tramite
  il servizio di schedulazione esistente, senza vincolare la richiesta a una fascia.
* **Calendario manuale**: scegli un giorno nei prossimi 10 giorni e una fascia
  disponibile; sono mostrati risorsa, durata e indicazioni del protocollo
  (contrasto, digiuno, note). Fasce gia' trascorse non sono selezionabili.

Puoi aggiungere note per il laboratorio (massimo 2000 caratteri). **Rivedi richiesta
esame** non esegue comandi; **Conferma e richiedi esame** completa la valutazione
Camunda con `requiresExam: true`, non approva ne' rifiuta il paziente.
Il backend richiede il task aperto del gruppo e assegnato all'operatore corrente.
Per il calendario ricostruisce la fascia dai servizi esistenti: risorsa e ora iniziale
devono ancora essere disponibili, mentre protocollo, ora finale e codice risorsa
non sono accettati dal browser come dati autorevoli. Fascia non disponibile: `409`;
combinazione senza protocollo, campi o data non validi: `400`.

Esempio automatico:

```json
{"examType":"TAC","bodyPart":"TORACE","schedulingPreference":"AUTO_FIRST_AVAILABLE","note":"Approfondimento"}
```

Esempio manuale (ID e data devono provenire dalle disponibilita' effettive):

```json
{"examType":"TAC","bodyPart":"TORACE","schedulingPreference":"MANUAL_CALENDAR","resourceId":1,"startTime":"2026-10-28T09:00:00","note":"Approfondimento"}
```

Il backend costruisce il payload, compresi identita' richiedente,
coda senza duplicati e variabili specifiche `chirurgo*` / `oncologo*`.
Una nuova richiesta automatica azzera i precedenti campi di scelta manuale,
evitando di riusare una vecchia fascia nei cicli successivi.
La richiesta manuale web registra `REQUEST_CREATED` dopo il completamento Camunda;
l'audit applicativo e il comando Camunda non sono una transazione distribuita.

Il BPMN include i mapping `requesterId` verso `chirurgoRequesterId` /
`oncologoRequesterId` e `examQueue` verso `examQueue`: senza questi mapping
Camunda scartava ID e coda al completamento dello specialista. Dopo l'aggiornamento
va distribuita la nuova versione del processo: le istanze gia' avviate restano
sulla loro versione e non acquisiscono automaticamente i nuovi mapping.
La coda conserva la logica esistente; lettura e completamento di specialisti
concorrenti non costituiscono un aggiornamento atomico della coda condivisa.

**La selezione non prenota uno slot** e non scrive una prenotazione nel database.
La Diagnostica puo' confermare, modificare o rifiutare la proposta dalla GUI,
con i controlli di conflitto della prenotazione.
La disponibilita' puo' cambiare anche dopo l'invio della richiesta.
I due percorsi diagnostici hanno ora ritorni separati al rispettivo specialista;
anche l'inserimento del referto e' disponibile nel portale.

### Verifiche del portale

```bash
./mvnw test
cd frontend
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

I test HTTP avviano Spring su una porta casuale con H2 in memoria e integrazione
Camunda simulata. I test browser verificano il portale con risposte API simulate.
Le verifiche diagnostiche includono conferma, modifica motivata, rifiuto,
recupero dopo timeout, decisioni lette dal database dopo il salvataggio,
concorrenza sulla stessa risorsa e doppio invio sullo stesso task.
La refertazione verifica inoltre testo obbligatorio e limite di lunghezza,
fine fascia al nanosecondo, slot completato e storico atomici, recupero dopo
timeout, correlazione della conferma e conflitto fra testi concorrenti.
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

I test usano per default `http://127.0.0.1:8081` e Camunda su
`http://127.0.0.1:8080`; gli indirizzi possono essere impostati tramite
`PDTA_LIVE_URL` e `PDTA_CAMUNDA_URL`. Non avvia o arresta i servizi.
Crea pazienti fittizi `GUI-TEST-*`, `GUI-ACTION-*`, `GUI-EXAM-*` e `GUI-LAB-*`, verifica conferma, registrazione,
visibilita' in entrambi i gruppi, persistenza dopo refresh e ripetizione del
completamento accesso. Verifica inoltre presa in carico, approvazione di entrambi
gli specialisti fino alle firme e alla generazione documento simulate, rifiuto
motivato e blocco di un invio ripetuto. Controlla variabili ed elementi BPMN reali.
Verifica anche richiesta automatica e manuale fino all'arrivo del task al laboratorio,
controllando le variabili reali per ruolo senza creare prenotazioni.
Verifica due richieste diagnostiche parallele, presa in carico dalla GUI,
rifiuto motivato e ritorno al nuovo task del richiedente corretto, mantenendo
aperta l'altra richiesta. Le letture attendono in modo limitato la propagazione
degli indici Camunda; un timeout resta un errore, non un successo.
Al termine cancella esclusivamente le proprie istanze ancora attive; quelle gia'
completate non vengono cancellate. La traccia storica resta visibile in Camunda;
l'evento di audit della richiesta manuale fittizia e le decisioni di rifiuto
restano nel database applicativo. I test live non occupano slot: conferma e modifica
della prenotazione vengono provate dai test HTTP con database isolato.

Per verificare anche la refertazione reale senza occupare slot nel database locale:

```bash
PDTA_REPORT_LIVE=true ./mvnw -Pweb-ui -Dtest=DiagnosticReportLiveTests test
```

Occorrono Camunda con BPMN/DMN e l'applicazione worker gia' attivi, Node.js e Chromium
Playwright installati. Il test avvia automaticamente un server temporaneo su porta
casuale e database H2 in memoria, crea due richieste parallele in Camunda e prepara
solo nel database di prova prenotazioni con fascia gia' terminata. Verifica prima
il rifiuto dell'invio prematuro, poi usa il browser per inviare i due referti e
controllarne il ritorno alle rispettive rivalutazioni. Non introduce API per
alterare l'orario o aggirare il controllo temporale. Al termine annulla il proprio
processo; lo storico Camunda rimane, mentre il database di prova viene scartato.
Il test e' saltato nella suite Java standard se la variabile non e' impostata;
il relativo scenario browser richiede le fixture fornite dal test Java e viene
saltato nell'esecuzione ordinaria di `npm run test:live`.

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
Avvia una sola applicazione Spring Boot con il profilo `web-ui`:

```bash
SPRING_PROFILES_ACTIVE=web-ui ./start-app.sh
```

Il backend espone le API e gestisce i worker automatici. Per lo sviluppo avvia
il frontend in un secondo terminale con `cd frontend && npm run dev`, poi apri
`http://127.0.0.1:5173`. Per servire anche il frontend dallo stesso JAR,
segui la sezione **Pacchetto unico** e apri `http://127.0.0.1:8081`.

Nel portale seleziona la vista Case Manager, Chirurgo, Oncologo o Diagnostica:
non servono terminali separati per gli operatori. Il selettore rimane dimostrativo,
senza autenticazione reale. L'avvio senza profilo `web-ui` mantiene disponibili
i worker, ma non le API operative del portale.

## Note Funzionali
*   Il portale specialista invia variabili di processo per la gestione degli esami: `requiresExam`, `examType`, `requesterRole`, `requesterName`. Consente la selezione manuale delle fasce disponibili.
*   Il portale diagnostica consente conferma della proposta, modifica motivata della fascia o rifiuto motivato. Le decisioni vengono salvate e gli eventi di prenotazione tracciati.
*   I referti sono gestiti per ruolo (`chirurgoExamResult`, `oncologoExamResult`) così ogni specialista visualizza il referto corretto.
*   Il backend salva e invia i referti; un errore di completamento richiede verifica, non equivale a successo.
*   **Configurazione dati:** Protocolli e risorse diagnostiche iniziali vengono caricati dai seeder; le fasce disponibili sono calcolate dal servizio di scheduling. La gestione amministrativa tramite GUI non e' ancora disponibile.
*   **KPI Giornalieri:** Sistema automatico di snapshot KPI schedulato giornalmente (configurabile via `application.yml`).
*   **Tracciabilità completa:** Ogni evento di scheduling registra chi ha richiesto e chi ha eseguito/refertato, garantendo visibilità operativa totale.
