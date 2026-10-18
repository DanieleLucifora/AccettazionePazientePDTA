# Guida di Sviluppo — PDTA Camunda
> Stato aggiornato al 25/04/2026  
> Stack: Java 21 · Spring Boot 3.5.8 · Camunda 8 (Zeebe) · H2 file-based · Maven

---

## Panoramica delle fasi

| Fase | Contenuto | Priorità | Dipendenze |
|------|-----------|----------|------------|
| 1 | Stati SlotStatus + aggiornamento audit | Alta | Nessuna |
| 2 | BPMN — Timer + task refertazione | Alta | Fase 1 |
| 3 | CLI Diagnostica — due fasi logiche | Alta | Fase 2 |
| 4 | CLI Specialista — allineamento UX | Media | Fase 3 |
| 5 | Prometheus — osservabilità operativa | Bassa | Nessuna (parallela) |
| 6 | GUI Web — da CLI a interfaccia grafica | Bassa/Tesi | Fase 3 completa + Fase 5 |

---

## FASE 1 — Prerequisiti tecnici

### 1.1 — Aggiungere stati `PROPOSED` e `RESERVED` a `SlotStatus`

**File:** `src/main/java/it/uni/pdta/pdta_camunda/scheduling/domain/enums/SlotStatus.java`

**Stato attuale:**
```java
public enum SlotStatus {
    BOOKED, IN_PROGRESS, COMPLETED, CANCELED, NO_SHOW
}
```

**Obiettivo:**
```java
public enum SlotStatus {
    PROPOSED,       // slot suggerito dallo scheduler, non confermato
    RESERVED,       // slot confermato dal laboratorio, esame non ancora eseguito
    IN_PROGRESS,    // esame in corso (timer scattato)
    COMPLETED,      // esame completato e refertato
    CANCELED,       // esame annullato
    NO_SHOW         // paziente non si è presentato
}
```

> **Nota:** `BOOKED` diventa `RESERVED` per chiarezza semantica. Aggiornare tutti i riferimenti
> a `BOOKED` nella codebase (BookingService, DiagnosticCli, BookingSlot).

**Come trovare tutti i riferimenti a BOOKED:**
```bash
grep -rn "BOOKED\|SlotStatus" src/main/java --include="*.java"
```

---

### 1.2 — Aggiungere tipo evento `EXAM_STARTED` a `ResourceEventType`

**File:** `src/main/java/it/uni/pdta/pdta_camunda/scheduling/domain/enums/ResourceEventType.java`

Aggiungere il valore `EXAM_STARTED` all'enum esistente.

---

### 1.3 — Aggiornare `ResourceEventService` per i nuovi stati

**File:** `src/main/java/it/uni/pdta/pdta_camunda/scheduling/service/ResourceEventService.java`

Modifiche da apportare:

1. In `logSlotAssigned()`: il payload deve riflettere stato `RESERVED` (non più `BOOKED`).
2. Aggiungere metodo `logExamStarted(BookingSlot slot, String actor, String correlationId)`:
   ```java
   public void logExamStarted(BookingSlot slot, String actor, String correlationId) {
       Map<String, Object> payload = new LinkedHashMap<>();
       payload.put("startedAt", LocalDateTime.now().toString());
       payload.put("status", SlotStatus.IN_PROGRESS.name());
       logEvent(
           slot.getResource().getId(),
           slot.getId(),
           slot.getExamProtocol().getId(),
           ResourceEventType.EXAM_STARTED,
           actor,
           correlationId,
           payload
       );
   }
   ```
3. In `logExamCompleted()`: aggiungere `completedAt` con timestamp reale (`LocalDateTime.now()`).

---

### 1.4 — Aggiornare `BookingService` per usare `RESERVED`

**File:** `src/main/java/it/uni/pdta/pdta_camunda/scheduling/service/BookingService.java`

Sostituire ogni assegnazione `SlotStatus.BOOKED` con `SlotStatus.RESERVED`.

---

## FASE 2 — BPMN: separare scheduling da refertazione

**File:** `src/main/resources/presa_in_carico_pdta.bpmn`

> ⚠ Aprire con **Camunda Modeler** (versione 5.x). Non modificare manualmente l'XML se si può evitare.

### 2.1 — Rinominare il task esistente

Selezionare `Activity_1nqhbxs` (attualmente "Esami Diagnostici") e rinominarlo in:
```
Scheduling Esame
```
Lasciare invariato `candidateGroups = LABORATORIO`.

### 2.2 — Aggiungere Intermediate Timer Catch Event

Dopo `Activity_1nqhbxs`, inserire un **Intermediate Timer Catch Event** con:
- **ID:** `Timer_bookedSlot`
- **Nome:** `Attendi data esame`
- **Timer definition type:** `Date`
- **Timer expression:** `=bookedSlotStart`

> `bookedSlotStart` è la variabile di processo già salvata in `completeTaskWithOutcome()`.
> Zeebe la legge come ISO-8601 string (es. `2026-04-26T10:00:00`).

### 2.3 — Aggiungere UserTask "Esecuzione e Refertazione"

Dopo il Timer Catch Event, inserire una nuova **UserTask** con:
- **ID:** `Activity_refertazione` (o nome descrittivo)
- **Nome:** `Esecuzione e Refertazione`
- **candidateGroups:** `LABORATORIO`

### 2.4 — Collegare il flusso

Il flusso finale deve essere:

```
[Scheduling Esame] (Activity_1nqhbxs)
        ↓
[Timer: Attendi data esame] (Timer_bookedSlot)
        ↓  (scatta automaticamente quando bookedSlotStart è raggiunto)
[Esecuzione e Refertazione] (Activity_refertazione)
        ↓
[Gateway_1mpo32s] → routing allo specialista (invariato)
```

Rimuovere il flusso diretto da `Activity_1nqhbxs` → `Gateway_1mpo32s` e collegarlo alla nuova task.

### 2.5 — Verificare il BPMN in Zeebe

Dopo aver salvato il `.bpmn`:
1. Aprire Camunda Modeler
2. Deploy → puntare al cluster locale (`http://127.0.0.1:26500`)
3. Verificare in **Camunda Operate** che il nuovo processo sia visibile

---

## FASE 3 — CLI Diagnostica: due fasi logiche

**File:** `src/main/java/it/uni/pdta/pdta_camunda/cli/DiagnosticCli.java`

### 3.1 — Distinguere i task nel loop di polling

Nel metodo `run()`, il polling attualmente filtra per `candidateGroups = LABORATORIO`.
Dopo la Fase 2, esistono due task con quel gruppo. Bisogna distinguerli per nome:

```java
// Sostituire la selezione del targetTask con:
for (CamundaRestClient.UserTask t : tasks) {
    boolean isCreated = "CREATED".equalsIgnoreCase(t.state);
    boolean hasGroup = t.candidateGroups != null && t.candidateGroups.contains("LABORATORIO");
    boolean alreadyHandled = handledTaskKeys.contains(t.userTaskKey);
    if (isCreated && hasGroup && !alreadyHandled) {
        targetTask = t;
        break;
    }
}
```

Nel `processTask()`, aggiungere il routing per nome:

```java
private void processTask(CamundaRestClient.UserTask task) throws Exception {
    String taskName = task.getName() != null ? task.getName() : "";
    if (taskName.contains("Scheduling")) {
        handleSchedulingTask(task);
    } else if (taskName.contains("Refertazione")) {
        handleRefertazioneTask(task);
    } else {
        // fallback per compatibilità con vecchio BPMN
        handleSchedulingTask(task);
    }
}
```

### 3.2 — Estrarre `handleSchedulingTask(task)`

Spostare la logica di scheduling (proposta slot, conferma/modifica/rifiuto) nel metodo dedicato.
Al completamento, il task viene chiuso con le variabili di booking (inclusa `bookedSlotStart`).
**Non chiedere il referto in questo metodo.**

Variabili da passare a Zeebe alla chiusura del task di scheduling:
```java
completeVariables.put("bookedSlotStart", String.valueOf(bookedSlot.getStartTime())); // es. "2026-04-26T10:00"
completeVariables.put("bookedSlotId", String.valueOf(bookedSlot.getId()));
completeVariables.put("bookedResourceCode", bookedSlot.getResource().getCode());
// NON passare examResult qui
```

### 3.3 — Creare `handleRefertazioneTask(task)`

Questo metodo si attiva solo quando il timer è scattato (data/ora esame raggiunta).
Logica:

```java
private void handleRefertazioneTask(CamundaRestClient.UserTask task) throws Exception {
    // 1. Recuperare bookedSlotId dalle variabili di processo
    String slotIdStr = (String) task.variables.get("bookedSlotId");
    BookingSlot slot = bookingService.findSlotById(Long.parseLong(slotIdStr));

    // 2. Aggiornare stato slot a IN_PROGRESS
    bookingService.updateSlotStatus(slot, SlotStatus.IN_PROGRESS);
    resourceEventService.logExamStarted(slot, currentUser.getId(), task.processInstanceKey);

    // 3. Mostrare info esame
    System.out.println("ESAME IN ESECUZIONE");
    System.out.println("Tipo: " + slot.getExamProtocol().getExamType());
    System.out.println("Risorsa: " + slot.getResource().getCode());

    // 4. Chiedere referto
    System.out.print("Inserisci il referto: ");
    String examResult = scanner.nextLine().trim();

    // 5. Aggiornare stato slot a COMPLETED
    bookingService.updateSlotStatus(slot, SlotStatus.COMPLETED);
    resourceEventService.logExamCompleted(slot, currentUser.getId(), task.processInstanceKey, examResult, null);

    // 6. Completare task Zeebe
    Map<String, Object> vars = new HashMap<>();
    vars.put("examResult", examResult);
    // + variabili ruolo (oncologoExamResult / chirurgoExamResult)
    restClient.assignTask(task.userTaskKey, currentUser.getId());
    restClient.completeTask(task.userTaskKey, vars);
}
```

### 3.4 — Gestire la modalità demo (slot nel passato)

Per permettere il testing senza attendere il timer reale, aggiungere un flag in `application.yml`:

```yaml
pdta:
  scheduling:
    demo-mode: true   # se true, il timer viene impostato a "adesso + 10 secondi"
```

Nel `handleSchedulingTask()`, se `demo-mode = true`, sovrascrivere `bookedSlotStart`:
```java
if (demoMode) {
    completeVariables.put("bookedSlotStart", LocalDateTime.now().plusSeconds(10).toString());
}
```

---

## FASE 4 — CLI Specialista: allineamento UX

**File:** `src/main/java/it/uni/pdta/pdta_camunda/cli/SpecialistCli.java`

### 4.1 — Mostrare stato "In attesa esecuzione esame"

Quando il processo ha `bookedSlotStart` ma non ancora `examResult`, visualizzare:

```java
Object bookedSlotStart = processVariables.get("bookedSlotStart");
Object examResult = processVariables.get("examResult");

if (bookedSlotStart != null && examResult == null) {
    System.out.println(colorize(
        "Esame schedulato per: " + bookedSlotStart + " — In attesa di esecuzione",
        Color.CYAN
    ));
}
```

### 4.2 — Mostrare il referto solo quando disponibile

Il referto (`examResult`) viene popolato solo dopo la Fase 3.2 (task di refertazione completata).
Lo specialista lo vede solo alla prossima apertura della sua task, non in tempo reale.

### 4.3 — Mostrare stato `RESERVED` nello storico

Se il booking ha `bookedSlotId` nelle variabili, mostrare:
```
Slot prenotato: [data/ora] — Stato: RESERVED
```

---

## FASE 5 — Prometheus: osservabilità operativa

### 5.1 — Aggiungere dipendenza in `pom.xml`

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 5.2 — Configurare `application.yml`

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  metrics:
    export:
      prometheus:
        enabled: true
  endpoint:
    prometheus:
      enabled: true
```

> `spring-boot-starter-actuator` è già presente nel `pom.xml` — nessuna dipendenza aggiuntiva richiesta.

### 5.3 — Iniettare `MeterRegistry` in `ResourceEventService`

```java
@RequiredArgsConstructor
public class ResourceEventService {

    private final MeterRegistry meterRegistry; // aggiungere al costruttore

    // In ogni metodo log, incrementare il counter relativo:
    public void logSlotAssigned(...) {
        // ... logica esistente ...
        meterRegistry.counter("pdta.slot.assigned").increment();
    }

    public void logExamCompleted(...) {
        // ... logica esistente ...
        meterRegistry.counter("pdta.exam.completed").increment();
    }

    public void logExamCanceled(...) {
        // ... logica esistente ...
        meterRegistry.counter("pdta.exam.canceled").increment();
    }
}
```

### 5.4 — Gauge per slot disponibili in `BookingService`

```java
@PostConstruct
public void registerMetrics() {
    Gauge.builder("pdta.slots.available", this, BookingService::countAvailableSlots)
         .description("Numero slot diagnostici disponibili")
         .register(meterRegistry);
}

private double countAvailableSlots() {
    return bookingSlotRepository.countByStatus(SlotStatus.PROPOSED);
}
```

### 5.5 — KPI giornaliero come Gauge in `KpiSnapshotService`

Dopo il calcolo snapshot, pubblicare il valore come gauge aggiornabile:

```java
AtomicInteger completedToday = new AtomicInteger(0);

// dopo calcolo snapshot:
completedToday.set(snapshot.getEsamiCompletati());
Gauge.builder("pdta.kpi.completed_today", completedToday, AtomicInteger::get)
     .register(meterRegistry);
```

### 5.6 — Configurare Prometheus localmente (opzionale)

Creare `docker-compose-monitoring.yml` nella root del progetto:

```yaml
version: '3.8'
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

Creare `prometheus.yml` nella root:

```yaml
scrape_configs:
  - job_name: 'pdta-camunda'
    scrape_interval: 15s
    static_configs:
      - targets: ['host.docker.internal:8081']
    metrics_path: /actuator/prometheus
```

Avvio:
```bash
docker-compose -f docker-compose-monitoring.yml up -d
```

Aprire Grafana su `http://localhost:3000` (admin/admin), aggiungere datasource Prometheus su `http://prometheus:9090`.

---

## FASE 6 — GUI Web: da CLI a interfaccia grafica

### 6.1 — Scelta tecnologica

Per questo progetto si consiglia **Thymeleaf** (server-side rendering integrato in Spring Boot):
- Zero configurazione aggiuntiva
- Stesso processo Maven
- Nessun build tool frontend separato

Alternativa **React** se si vuole separazione frontend/backend — richiede npm, build separato, CORS.

### 6.2 — Aggiungere dipendenza Thymeleaf in `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

### 6.3 — Struttura cartelle da creare

```
src/main/java/.../
    web/
        controller/
            CaseManagerController.java
            DiagnosticController.java
            SpecialistController.java
            AdminController.java
        dto/
            TaskDto.java
            SlotDecisionDto.java
            ReportDto.java
        config/
            SecurityConfig.java

src/main/resources/
    templates/
        layout.html              (template base con navbar)
        dashboard-casemanager.html
        dashboard-specialist.html
        dashboard-diagnostic-scheduling.html
        dashboard-diagnostic-report.html
        dashboard-admin.html     (KPI + metriche)
    static/
        css/
            style.css
```

### 6.4 — Configurare Spring Security con ruoli in memoria

**File:** `web/config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/diagnostic/**").hasRole("LABORATORIO")
                .requestMatchers("/specialist/**").hasAnyRole("CHIRURGO", "ONCOLOGO")
                .requestMatchers("/casemanager/**").hasRole("CASE_MANAGER")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.loginPage("/login").permitAll())
            .logout(logout -> logout.permitAll());
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        // Utenti hardcoded per prototipo
        UserDetails lab = User.withDefaultPasswordEncoder()
            .username("laboratorio").password("lab123").roles("LABORATORIO").build();
        UserDetails chirurgo = User.withDefaultPasswordEncoder()
            .username("chirurgo").password("chir123").roles("CHIRURGO").build();
        UserDetails oncologo = User.withDefaultPasswordEncoder()
            .username("oncologo").password("onco123").roles("ONCOLOGO").build();
        UserDetails cm = User.withDefaultPasswordEncoder()
            .username("casemanager").password("cm123").roles("CASE_MANAGER").build();
        return new InMemoryUserDetailsManager(lab, chirurgo, oncologo, cm);
    }
}
```

### 6.5 — Endpoints REST per ciascun ruolo

**DiagnosticController:**
| Metodo | Path | Descrizione |
|--------|------|-------------|
| GET | `/diagnostic/tasks` | Lista task scheduling pendenti per LABORATORIO |
| POST | `/diagnostic/tasks/{taskId}/schedule` | Conferma/modifica/rifiuta slot |
| GET | `/diagnostic/report-tasks` | Lista task refertazione disponibili |
| POST | `/diagnostic/report-tasks/{taskId}/report` | Invia referto |

**SpecialistController:**
| Metodo | Path | Descrizione |
|--------|------|-------------|
| GET | `/specialist/tasks` | Lista task validazione per ruolo |
| POST | `/specialist/tasks/{taskId}/validate` | Valida paziente + richiedi esami |
| GET | `/specialist/tasks/{taskId}/status` | Stato esame (RESERVED, COMPLETED + referto) |

**AdminController:**
| Metodo | Path | Descrizione |
|--------|------|-------------|
| GET | `/admin/kpi` | KpiDailySnapshot ultimi 30 giorni |
| GET | `/admin/metrics` | Proxy dati Prometheus (opzionale) |

### 6.6 — Template Thymeleaf: Dashboard Laboratorio (esempio)

**File:** `src/main/resources/templates/dashboard-diagnostic-scheduling.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><title>Scheduling Esami</title></head>
<body>
  <h1>Richieste Scheduling in attesa</h1>
  <table>
    <thead>
      <tr><th>Task ID</th><th>Paziente</th><th>Esame</th><th>Richiesto da</th><th>Azioni</th></tr>
    </thead>
    <tbody>
      <tr th:each="task : ${tasks}">
        <td th:text="${task.taskId}"></td>
        <td th:text="${task.patientData}"></td>
        <td th:text="${task.examType}"></td>
        <td th:text="${task.requesterName}"></td>
        <td>
          <form th:action="@{/diagnostic/tasks/{id}/schedule(id=${task.taskId})}" method="post">
            <input type="hidden" name="decision" value="CONFIRM"/>
            <button type="submit">Conferma slot proposto</button>
          </form>
        </td>
      </tr>
    </tbody>
  </table>
</body>
</html>
```

### 6.7 — Dashboard Admin con KPI

Visualizzare `KpiDailySnapshot` in tabella e grafico (Chart.js incluso via CDN):

```html
<canvas id="kpiChart"></canvas>
<script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
<script>
  const ctx = document.getElementById('kpiChart');
  new Chart(ctx, {
    type: 'bar',
    data: {
      labels: /*[[${kpiDates}]]*/ [],
      datasets: [{
        label: 'Esami completati',
        data: /*[[${kpiCompleted}]]*/ []
      }]
    }
  });
</script>
```

---

## Note finali sull'ordine di esecuzione

```
Fase 1  ──→  Fase 2  ──→  Fase 3  ──→  Fase 4
                                          │
                            Fase 5 ───────┘
                              │
                            Fase 6
```

- **Fase 5 (Prometheus)** è indipendente e può essere eseguita in parallelo alle Fasi 1-4
- **Fase 6 (GUI)** richiede che le Fasi 1-3 siano completate (serve l'API REST da esporre ai controller web)
- Il **demo-mode** (Fase 3.4) permette di testare il flusso completo senza aspettare i timer reali

---

*Generato il 25/04/2026 — progetto pdta-camunda*
