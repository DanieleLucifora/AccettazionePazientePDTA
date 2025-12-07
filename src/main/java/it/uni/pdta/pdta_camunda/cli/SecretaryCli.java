package it.uni.pdta.pdta_camunda.cli;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI Segreteria/CaseManager
 *
 * Comportamento:
 * 1. Chiede se vuoi inserire nuovo paziente
 * 2. Raccoglie dati paziente interattivamente
 * 3. Avvia processo PDTA tramite CamundaClient
 * 4. Cerca task "Raccolta Dati Accesso" tramite API REST v2
 * 5. Assegna e completa task automaticamente
 *
 */
@SpringBootApplication
public class SecretaryCli implements CommandLineRunner {

    private final CamundaClient camundaClient;
    private final CamundaRestClient restClient;

    public SecretaryCli() {
        // Crea CamundaClient manualmente per CLI (senza Spring Boot auto-configuration)
        this.camundaClient = CamundaClient.newClientBuilder()
                .grpcAddress(URI.create("http://127.0.0.1:26500"))
                .restAddress(URI.create("http://localhost:8080"))
                .build();
        this.restClient = new CamundaRestClient("http://localhost:8080/v2");
    }

    public static void main(String[] args) {
        System.setProperty("logging.level.root", "ERROR");
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("spring.main.log-startup-info", "false");
        System.setProperty("spring.main.web-application-type", "none");

        SpringApplication.run(SecretaryCli.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        printHeader();

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

            // Avvia processo
            System.out.println("\n>>> Avvio processo PDTA...");
            ProcessInstanceEvent instance = camundaClient.newCreateInstanceCommand()
                    .bpmnProcessId("presa_in_carico_pdta")
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();

            System.out.println(colorize("✓ Processo avviato con successo!", Color.GREEN));
            System.out.println(colorize("Process Instance Key: " + instance.getProcessInstanceKey(), Color.GREEN));
            System.out.println();

            // Attendi creazione task
            System.out.println(">>> Attendo creazione task 'Raccolta Dati Accesso'...");
            Thread.sleep(3000);

            // Cerca il task tramite API REST
            System.out.println(">>> Ricerca task 'Raccolta Dati Accesso'...");
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
            printSuccess(instance.getProcessInstanceKey(), patientID, diagnosiCode);

            // Chiedi se continuare con un altro paziente
            System.out.print("\nVuoi inserire un altro paziente? [y/N]: ");
            String again = scanner.nextLine().trim();
            continueLoop = again.equalsIgnoreCase("y");
        }

        scanner.close();
        System.exit(0);
    }

    private void printHeader() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║    Sistema PDTA - Segreteria/CaseManager       ║");
        System.out.println("╚════════════════════════════════════════════════╝");
    }

    private void printSuccess(long processInstanceKey, String patientID, String diagnosiCode) {
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║           PROCESSO AVVIATO CON SUCCESSO        ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println(colorize("Process Instance Key: " + processInstanceKey, Color.BLUE));
        System.out.println(colorize("Patient ID: " + patientID, Color.CYAN));
        System.out.println(colorize("Codice Diagnosi: " + diagnosiCode, Color.CYAN));
        System.out.println();
        System.out.println("Il processo ora attende le revisioni dei medici GOM1 e GOM2.");
        System.out.println();
        System.out.println(colorize("Prossimi passi:", Color.YELLOW));
        System.out.println("1. Avvia Medico1 in una shell separata:");
        System.out.println(colorize("   ./start-doctor1.sh", Color.GREEN));
        System.out.println();
        System.out.println("2. Avvia Medico2 in un'altra shell:");
        System.out.println(colorize("   ./start-doctor2.sh", Color.GREEN));
        System.out.println();
        System.out.println("3. Monitora su Operate: http://localhost:8080/operate");
        System.out.println();
    }

    private String colorize(String text, Color color) {
        return color.code + text + Color.RESET.code;
    }

    private enum Color {
        GREEN("\033[0;32m"),
        BLUE("\033[0;34m"),
        YELLOW("\033[1;33m"),
        RED("\033[0;31m"),
        CYAN("\033[0;36m"),
        RESET("\033[0m");

        final String code;

        Color(String code) {
            this.code = code;
        }
    }
}
