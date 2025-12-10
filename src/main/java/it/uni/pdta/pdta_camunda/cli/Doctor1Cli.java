package it.uni.pdta.pdta_camunda.cli;

import java.io.Console;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI Medico 1 (GOM1)
 *
 * Comportamento:
 * 1. Polling automatico (ogni 5s) per task "Revisione Medico GOM 1"
 * 2. Quando trova task, mostra dati paziente
 * 3. Richiede codice segreto medico
 * 4. Richiede validazione (approva/rifiuta)
 * 5. Assegna e completa task tramite API REST v2
 *
 */
public class Doctor1Cli {

    private static final int POLL_INTERVAL_MS = 5000;
    private final CamundaRestClient restClient;
    private final Scanner scanner;

    public Doctor1Cli() {
        this.restClient = new CamundaRestClient("http://localhost:8080/v2");
        this.scanner = new Scanner(System.in);
    }

    public static void main(String[] args) throws Exception {
        Doctor1Cli cli = new Doctor1Cli();
        cli.run(args);
    }

    public void run(String... args) throws Exception {
        printHeader();

        System.out.println(colorize(">>> Monitoraggio task 'Revisione Medico GOM 1'...", Color.CYAN));
        System.out.println(colorize("(Premere Ctrl+C per uscire)", Color.YELLOW));
        System.out.println();

        boolean taskFound = false;

        while (true) {
            try {
                // Cerca task GOM1 con state CREATED
                List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(Map.of());

                CamundaRestClient.UserTask gom1Task = null;
                for (var task : tasks) {
                    if (task.name.equals("Revisione Medico GOM 1") && task.state.equals("CREATED")) {
                        gom1Task = task;
                        break;
                    }
                }

                if (gom1Task != null) {
                    taskFound = true;
                    processTask(gom1Task);

                    // Chiedi se continuare il monitoraggio
                    System.out.print("\nVuoi continuare a monitorare altri task GOM1? [Y/n]: ");
                    String continueMonitoring = scanner.nextLine().trim();
                    if (continueMonitoring.equalsIgnoreCase("n")) {
                        System.out.println(colorize("\n⚠ Uscita dal sistema GOM1.", Color.YELLOW));
                        break;
                    }

                    System.out.println();
                    System.out.println(colorize(">>> Ripresa monitoraggio...", Color.CYAN));
                    System.out.println();
                    taskFound = false;

                } else {
                    if (!taskFound) {
                        System.out.print("\r" + colorize("In attesa di task GOM1... (polling ogni " +
                                (POLL_INTERVAL_MS / 1000) + "s)", Color.YELLOW) + "   ");
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }

            } catch (InterruptedException e) {
                System.out.println("\n\n⚠ Monitoraggio interrotto dall'utente.");
                break;
            } catch (Exception e) {
                System.err.println("\n✗ Errore durante il monitoraggio: " + e.getMessage());
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }

        scanner.close();
        System.exit(0);
    }

    private void processTask(CamundaRestClient.UserTask task) throws Exception {
        System.out.println("\r" + colorize("✓ Trovato task GOM1 in attesa!", Color.GREEN) + "                    ");
        System.out.println();

        System.out.println(colorize("=== TASK DISPONIBILE ===", Color.BLUE));
        System.out.println("Task Key: " + task.userTaskKey);
        System.out.println("Process Instance: " + task.processInstanceKey);
        System.out.println("Nome: " + task.name);
        System.out.println();

        // Mostra dati paziente (le variabili sono disponibili nel processo)
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║            DATI PAZIENTE DA VALIDARE           ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println(colorize("Process Instance Key: " + task.processInstanceKey, Color.CYAN));
        System.out.println();

        // Autenticazione medico
        System.out.println(colorize("=== AUTENTICAZIONE MEDICO GOM1 ===", Color.YELLOW));

        String secretCode = readPassword("Inserisci il tuo codice segreto: ");
        if (secretCode == null || secretCode.isEmpty()) {
            System.err.println("✗ Codice segreto obbligatorio");
            return;
        }

        System.out.println();

        // Validazione paziente
        System.out.println(colorize("=== VALIDAZIONE PAZIENTE ===", Color.YELLOW));
        System.out.print("Approvi l'inserimento del paziente nel PDTA? [Y/n]: ");
        String approved = scanner.nextLine().trim();

        boolean gom1Approved = !approved.equalsIgnoreCase("n");

        System.out.print("Note di validazione: ");
        String gom1Note = scanner.nextLine().trim();
        if (gom1Note.isEmpty()) {
            gom1Note = gom1Approved ?
                    "Paziente idoneo per PDTA - GOM1" :
                    "Paziente non idoneo per PDTA - GOM1";
        }

        // Riepilogo
        System.out.println();
        System.out.println(colorize("=== RIEPILOGO ===", Color.YELLOW));
        System.out.println("Approvazione: " + gom1Approved);
        System.out.println("Note: " + gom1Note);
        System.out.println("Codice segreto: [********]");
        System.out.println();

        System.out.print("Confermi e vuoi completare la revisione? [Y/n]: ");
        String confirm = scanner.nextLine().trim();
        if (confirm.equalsIgnoreCase("n")) {
            System.out.println(colorize("⚠ Operazione annullata. Task non completato.", Color.YELLOW));
            return;
        }

        // Assegna task
        System.out.println();
        System.out.println(">>> Assegnazione task a 'demo'...");
        restClient.assignTask(task.userTaskKey, "demo");
        System.out.println(colorize("✓ Task assegnato", Color.GREEN));

        // Completa task
        System.out.println(">>> Completamento task GOM1...");
        Map<String, Object> taskVariables = new HashMap<>();
        taskVariables.put("gom1Approved", gom1Approved);
        taskVariables.put("gom1Note", gom1Note);
        taskVariables.put("gom1SecretCode", secretCode);

        restClient.completeTask(task.userTaskKey, taskVariables);

        System.out.println(colorize("✓ Revisione GOM1 completata con successo!", Color.GREEN));
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║        VALIDAZIONE GOM1 COMPLETATA             ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println(colorize("Il processo ora procederà con la firma digitale GOM1", Color.CYAN));
        System.out.println(colorize("e attende la revisione del medico GOM2.", Color.CYAN));
    }

    private void printHeader() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════╗");
        System.out.println("║        Sistema PDTA - Medico 1 (GOM1)          ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println();
    }

    private String readPassword(String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] passwordChars = console.readPassword(prompt);
            return passwordChars != null ? new String(passwordChars) : null;
        } else {
            // Fallback se console non disponibile (es. IDE)
            System.out.print(prompt);
            return scanner.nextLine().trim();
        }
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
