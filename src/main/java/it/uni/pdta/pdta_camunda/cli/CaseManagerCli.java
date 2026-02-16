package it.uni.pdta.pdta_camunda.cli;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import it.uni.pdta.pdta_camunda.model.CaseManager;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI Case Manager (ex Segreteria)
 *
 * Comportamento:
 * 1. Login simulato come Case Manager
 * 2. Chiede se vuoi inserire nuovo paziente
 * 3. Raccoglie dati paziente interattivamente
 * 4. Avvia processo PDTA tramite CamundaClient
 * 5. Cerca task "Raccolta Dati Accesso" tramite API REST v2
 * 6. Assegna e completa task automaticamente
 *
 */
public class CaseManagerCli {

    private final CamundaClient camundaClient;
    private final CamundaRestClient restClient;
    private final CaseManager currentUser;

    public CaseManagerCli() {
        // Crea CamundaClient manualmente per CLI
        this.camundaClient = CamundaClient.newClientBuilder()
                .grpcAddress(URI.create("http://127.0.0.1:26500"))
                .restAddress(URI.create("http://localhost:8080"))
                .build();
        this.restClient = new CamundaRestClient("http://localhost:8080/v2");
        
        // Login simulato
        this.currentUser = new CaseManager("CM001", "Giulia", "Verdi");
    }

    public static void main(String[] args) throws Exception {
        CaseManagerCli cli = new CaseManagerCli();
        cli.run(args);
    }

    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        printHeader();

        System.out.println("Benvenuto, " + currentUser.getRuolo() + " " + currentUser.getNome() + " " + currentUser.getCognome());
        System.out.println("Unità Operativa: " + currentUser.getUnitaOperativa());
        System.out.println();

        boolean continueLoop = true;
        while (continueLoop) {
            System.out.print("\nVuoi inserire un nuovo paziente nel PDTA? [y/N]: ");
            String confirm = scanner.nextLine().trim();
            if (!confirm.equalsIgnoreCase("y")) {
                System.out.println("\n⚠ Operazione annullata.");
                break;
            }

            // Raccolta dati paziente
            System.out.println("\n" + colorize("=== RACCOLTA DATI PAZIENTE ===", Color.GREEN));
            System.out.println();

            System.out.print("Patient ID (es. 070686): ");
            String patientID = scanner.nextLine().trim();
            if (patientID.isEmpty()) {
                System.err.println("✗ Patient ID obbligatorio");
                continue;
            }

            System.out.print("Dati Paziente (es. Mario Rossi - CF: RSSMRA80A01H501Z): ");
            String patientData = scanner.nextLine().trim();
            if (patientData.isEmpty()) {
                System.err.println("✗ Dati paziente obbligatori");
                continue;
            }

            System.out.print("Codice Diagnosi (es. 6401): ");
            String diagnosiCode = scanner.nextLine().trim();
            if (diagnosiCode.isEmpty()) {
                System.err.println("✗ Codice diagnosi obbligatorio");
                continue;
            }

            System.out.print("URL Cartella Clinica [opzionale]: ");
            String clinicalFileUrl = scanner.nextLine().trim();

            // Riepilogo
            System.out.println("\n" + colorize("=== RIEPILOGO DATI ===", Color.YELLOW));
            System.out.println("Patient ID: " + patientID);
            System.out.println("Dati Paziente: " + patientData);
            System.out.println("Codice Diagnosi: " + diagnosiCode);
            System.out.println("URL Cartella: " + (clinicalFileUrl.isEmpty() ? "[non fornito]" : clinicalFileUrl));
            System.out.println();

            System.out.print("Confermi i dati e vuoi avviare il processo? [Y/n]: ");
            String confirmStart = scanner.nextLine().trim();
            if (confirmStart.equalsIgnoreCase("n")) {
                System.out.println("\n⚠ Operazione annullata.");
                continue;
            }

            // Prepara variabili
            Map<String, Object> variables = new HashMap<>();
            variables.put("patientID", patientID);
            variables.put("patientData", patientData);
            variables.put("diagnosiCode", diagnosiCode);
            if (!clinicalFileUrl.isEmpty()) {
                variables.put("clinicalFileUrl", clinicalFileUrl);
            }
            variables.put("caseManagerId", currentUser.getId()); // Tracciamo chi ha avviato

            try {
                // 1. Avvia istanza processo
                System.out.print("Avvio processo 'presa_in_carico_pdta'... ");
                ProcessInstanceEvent instance = camundaClient.newCreateInstanceCommand()
                        .bpmnProcessId("presa_in_carico_pdta")
                        .latestVersion()
                        .variables(variables)
                        .send()
                        .join();

                System.out.println(colorize("Processo avviato con successo!", Color.GREEN));
                System.out.println("Process Instance Key: " + instance.getProcessInstanceKey());

                // 2. Cerca il task "Raccolta Dati Accesso" (User Task)
                System.out.print("Ricerca task 'Raccolta Dati Accesso'... ");
                Thread.sleep(3000); // Attesa propagazione

                String processInstanceKey = String.valueOf(instance.getProcessInstanceKey());

                CamundaRestClient.UserTask task = null;
                for (int i = 0; i < 10; i++) {
                    List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(Map.of());
                    System.out.println(">>> Tentativo " + (i + 1) + "/10 - Task trovati: " + tasks.size());
                    for (var t : tasks) {
                        System.out.println("    - Task: '" + t.name + "' (ProcessInstance: " + t.processInstanceKey + ", State: " + t.state + ")");
                    }
                    for (var t : tasks) {
                        if (t.processInstanceKey.equals(processInstanceKey) &&
                            t.name.equals("Raccolta Dati Accesso") &&
                            t.state.equals("CREATED")) {
                            task = t;
                            break;
                        }
                    }
                    if (task != null) break;
                    Thread.sleep(1000);
                }

                if (task == null) {
                    System.err.println("✗ Task 'Raccolta Dati Accesso' non trovato");
                    System.err.println("Verifica su Operate: http://localhost:8080/operate");
                    continue;
                }

                System.out.println(colorize("✓ Task trovato: " + task.name + " (Key: " + task.userTaskKey + ")", Color.GREEN));

                // Assegna task
                System.out.println(">>> Assegnazione task a 'demo'...");
                restClient.assignTask(task.userTaskKey, "demo");
                System.out.println(colorize("✓ Task assegnato", Color.GREEN));

                // Completa task
                System.out.println(">>> Completamento task con dati paziente...");
                restClient.completeTask(task.userTaskKey, variables);
                System.out.println(colorize("✓ Task 'Raccolta Dati Accesso' completato con successo!", Color.GREEN));

                // Riepilogo finale
                System.out.println();
                System.out.println(colorize("Process Instance Key: " + instance.getProcessInstanceKey(), Color.GREEN));

                System.out.println("\n Paziente inserito correttamente.");

                // Chiedi se continuare con un altro paziente
                System.out.print("\nVuoi inserire un altro paziente? [y/N]: ");
                String again = scanner.nextLine().trim();
                continueLoop = again.equalsIgnoreCase("y");
            } catch (Exception e) {
                System.err.println("\n✗ Errore durante l'esecuzione: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        scanner.close();
        System.exit(0);
    }

    private void printHeader() {
        System.out.println(colorize("╔════════════════════════════════════════════════╗", Color.CYAN));
        System.out.println(colorize("║        PDTA CAMUNDA - CASE MANAGER CLI         ║", Color.CYAN));
        System.out.println(colorize("╚════════════════════════════════════════════════╝", Color.CYAN));
        System.out.println();
    }

    // Utility colori console
    enum Color { RED, GREEN, YELLOW, BLUE, CYAN, RESET }
    private String colorize(String text, Color color) {
        String code = switch (color) {
            case RED -> "\u001B[31m";
            case GREEN -> "\u001B[32m";
            case YELLOW -> "\u001B[33m";
            case BLUE -> "\u001B[34m";
            case CYAN -> "\u001B[36m";
            default -> "\u001B[0m";
        };
        return code + text + "\u001B[0m";
    }
}
