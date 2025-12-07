# Workflow accettazione paziente PDTA

![Diagramma BPMN](/presa_in_carico_pdta.png)

## Descrizione del Progetto
Il progetto implementa un workflow dimostrativo focalizzato sulla fase di accettazione di un paziente all'interno di un Percorso Diagnostico Terapeutico Assistenziale (PDTA). Il sistema automatizza i passaggi preliminari di verifica e validazione necessari per la presa in carico.

Il flusso operativo prevede:
1.  **Registrazione:** Inserimento dati paziente tramite interfaccia amministrativa.
2.  **Valutazione Automatica:** Verifica dei criteri di inclusione tramite regole DMN.
3.  **Approvazione Medica:** Workflow sequenziale di validazione da parte di due livelli medici (GOM 1 e GOM 2).
4.  **Finalizzazione:** Generazione automatica del documento PDTA con apposizione di firme digitali simulate.

## Tecnologie Utilizzate
*   **Linguaggio:** Java 21
*   **Framework:** Spring Boot 3.5.8
*   **Orchestrator:** Camunda Platform 8 (Zeebe Engine)
*   **Build Tool:** Maven

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
Il sistema è composto da diverse applicazioni CLI che devono essere avviate in finestre di terminale separate, nel seguente ordine:

**Terminale 1: Applicazione Principale (Worker e Controller)**
```bash
./start-app.sh
```

**Terminale 2: Interfaccia Segreteria (Avvio Processi)**
```bash
./start-secretary.sh
```

**Terminale 3: Interfaccia Medico 1 (GOM 1)**
```bash
./start-doctor1.sh
```

**Terminale 4: Interfaccia Medico 2 (GOM 2)**
```bash
./start-doctor2.sh
```
