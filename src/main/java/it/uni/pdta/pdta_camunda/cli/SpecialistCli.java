package it.uni.pdta.pdta_camunda.cli;

import it.uni.pdta.pdta_camunda.model.Chirurgo;
import it.uni.pdta.pdta_camunda.model.Oncologo;
import it.uni.pdta.pdta_camunda.model.OperatoreSanitario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * CLI Specialista (Chirurgo / Oncologo)
 *
 * Comportamento:
 * 1. Login: Scelta ruolo (Chirurgo o Oncologo)
 * 2. Polling automatico per task nel Candidate Group specifico (CHIRURGHI o ONCOLOGI)
 * 3. Visualizzazione dati paziente
 * 4. Validazione (Approva/Rifiuta) -> invia variabile generica "isApproved"
 *
 */
public class SpecialistCli {

    private static final int POLL_INTERVAL_MS = 5000;
    private final CamundaRestClient restClient;
    private final Scanner scanner;
    private OperatoreSanitario currentUser;
    private String candidateGroup;

    public SpecialistCli() {
        this.restClient = new CamundaRestClient("http://localhost:8080/v2");
        this.scanner = new Scanner(System.in);
    }

    public static void main(String[] args) throws Exception {
        SpecialistCli cli = new SpecialistCli();
        cli.run(args);
    }

    public void run(String... args) throws Exception {
        printHeader();

        // Login e Selezione Ruolo
        login();

        System.out.println(colorize(">>> Monitoraggio task per gruppo '" + candidateGroup + "'...", Color.CYAN));
        System.out.println(colorize("(Premere Ctrl+C per uscire)", Color.YELLOW));
        System.out.println();

        boolean taskFound = false;

        while (true) {
            try {
                // Cerca task 
                Map<String, Object> searchFilter = new HashMap<>();
                
                List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(searchFilter);
                
                // Filtro Client-side (Stato e Gruppo)
                CamundaRestClient.UserTask targetTask = null;
                if (tasks != null) {
                    for (CamundaRestClient.UserTask t : tasks) {
                        boolean isCreated = "CREATED".equalsIgnoreCase(t.state);
                        boolean hasGroup = t.candidateGroups != null && t.candidateGroups.contains(candidateGroup);
                        
                        if (isCreated && hasGroup) {
                            targetTask = t;
                            break;
                        }
                    }
                }

                if (targetTask != null) {
                    taskFound = true;
                    
                    // Recupera variabili aggiornate
                    targetTask.variables = restClient.getTaskVariables(targetTask.userTaskKey);
                    processTask(targetTask);

                    // Chiedi se continuare il monitoraggio
                    System.out.print("\nVuoi continuare a monitorare altri task? [Y/n]: ");
                    String continueMonitoring = scanner.nextLine().trim();
                    if (continueMonitoring.equalsIgnoreCase("n")) {
                        System.out.println(colorize("\n⚠ Uscita dal sistema.", Color.YELLOW));
                        break;
                    }

                    System.out.println();
                    System.out.println(colorize(">>> Ripresa monitoraggio...", Color.CYAN));
                    System.out.println();
                    taskFound = false;

                } else {
                    if (!taskFound) {
                        System.out.print("\r" + colorize("In attesa di task per " + currentUser.getRuolo() + "... (polling ogni " +
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

    private void login() {
        System.out.println("Seleziona il tuo ruolo:");
        System.out.println("1. Chirurgo");
        System.out.println("2. Oncologo");
        System.out.print("Scelta [1-2]: ");
        
        String choice = scanner.nextLine().trim();
        
        if (choice.equals("1")) {
            this.currentUser = new Chirurgo("CH001", "Marco", "Bianchi", "Chirurgia Generale", "Toracica");
            this.candidateGroup = "CHIRURGHI";
        } else if (choice.equals("2")) {
            this.currentUser = new Oncologo("ON001", "Luigi", "Rossi", "Oncologia Medica");
            this.candidateGroup = "ONCOLOGI";
        } else {
            System.out.println("Scelta non valida. Default: Chirurgo.");
            this.currentUser = new Chirurgo("CH001", "Marco", "Bianchi", "Chirurgia Generale", "Toracica");
            this.candidateGroup = "CHIRURGHI";
        }
        
        System.out.println("\nBenvenuto, " + currentUser.getRuolo() + " " + currentUser.getNome() + " " + currentUser.getCognome());
        System.out.println("Gruppo di lavoro: " + candidateGroup);
        System.out.println();
    }

    private void processTask(CamundaRestClient.UserTask task) throws Exception {
        System.out.println("\r" + colorize("✓ Trovato task in attesa!", Color.GREEN) + "                    ");
        System.out.println();

        System.out.println(colorize("=== TASK DISPONIBILE: " + task.name + " ===", Color.BLUE));
        
        // Recupera variabili del task (dati paziente)        
        String patientID = (String) task.variables.getOrDefault("patientID", "N/A");
        String patientData = (String) task.variables.getOrDefault("patientData", "N/A");
        String diagnosiCode = (String) task.variables.getOrDefault("diagnosiCode", "N/A");
        String clinicalFileUrl = (String) task.variables.getOrDefault("clinicalFileUrl", "N/A");

        System.out.println("Paziente ID: " + patientID);
        System.out.println("Dati: " + patientData);
        System.out.println("Diagnosi: " + diagnosiCode);
        System.out.println("Cartella Clinica: " + clinicalFileUrl);
        System.out.println();

        System.out.println("Devi validare l'idoneità del paziente per il percorso chirurgico/oncologico.");
        System.out.print("Approvare il paziente? [y/N]: ");
        String approval = scanner.nextLine().trim();
        
        boolean isApproved = approval.equalsIgnoreCase("y");
        
        // Conferma finale
        System.out.println("Stai per " + (isApproved ? "APPROVARE" : "RIFIUTARE") + " il paziente.");
        System.out.print("Confermi operazione? [Y/n]: ");
        String confirm = scanner.nextLine().trim();
        
        if (confirm.equalsIgnoreCase("n")) {
            System.out.println("Operazione annullata. Il task rimane in attesa.");
            return;
        }

        // Esegui task
        Map<String, Object> completeVariables = new HashMap<>();
        
        // Usiamo la variabile generica "isApproved" come farebbe il form unico.
        // Sarà il BPMN tramite Output Mapping a trasformarla in "chirurgoApproved" o "oncologoApproved"
        completeVariables.put("isApproved", isApproved);
        
        completeVariables.put("validatorId", currentUser.getId());
        completeVariables.put("validatorRole", currentUser.getRuolo());

        System.out.print("Invio esito a Camunda... ");
        restClient.assignTask(task.userTaskKey, currentUser.getId());
        restClient.completeTask(task.userTaskKey, completeVariables);
        System.out.println(colorize("COMPLETATO", Color.GREEN));
    }

    private void printHeader() {
        System.out.println(colorize("╔════════════════════════════════════════════════╗", Color.CYAN));
        System.out.println(colorize("║         PDTA CAMUNDA - SPECIALIST CLI          ║", Color.CYAN));
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
