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
        // Mostra il referto relativo al ruolo corrente; fallback su examResult per compatibilita
        String roleSpecificExamResult = "CHIRURGHI".equals(candidateGroup)
            ? (String) task.variables.get("chirurgoExamResult")
            : (String) task.variables.get("oncologoExamResult");
        String examResult = roleSpecificExamResult;
        if (examResult == null || examResult.isEmpty()) {
            examResult = (String) task.variables.get("examResult");
        }

        System.out.println("Paziente ID: " + patientID);
        System.out.println("Dati: " + patientData);
        System.out.println("Diagnosi: " + diagnosiCode);
        System.out.println("Cartella Clinica: " + clinicalFileUrl);
        
        if (examResult != null && !examResult.isEmpty()) {
            System.out.println(colorize("REFERTO ESAMI: " + examResult, Color.YELLOW));
        }
        System.out.println();

        System.out.println("Devi validare l'idoneità del paziente per il percorso chirurgico/oncologico.");
        System.out.println("Opzioni:");
        System.out.println("1. APPROVA paziente");
        System.out.println("2. RIFIUTA paziente");
        System.out.println("3. RICHIEDI ESAMI diagnostici");
        System.out.print("Scelta [1-3]: ");
        
        String choice = scanner.nextLine().trim();
        
        boolean isApproved = false;
        boolean requiresExam = false;
        String examType = "";
        String note = "";

        switch (choice) {
            case "1":
                isApproved = true;
                requiresExam = false;
                System.out.print("Inserisci note (opzionale): ");
                note = scanner.nextLine().trim();
                break;
            case "2":
                isApproved = false;
                requiresExam = false;
                System.out.print("Inserisci motivo rifiuto: ");
                note = scanner.nextLine().trim();
                break;
            case "3":
                isApproved = false; // Sospeso temporaneamente
                requiresExam = true;
                System.out.print("Inserisci tipo esame richiesto (es. TAC, Biopsia): ");
                examType = scanner.nextLine().trim();
                if (examType.isEmpty()) examType = "Approfondimento Generico";
                System.out.print("Inserisci note per il laboratorio: ");
                note = scanner.nextLine().trim();
                break;
            default:
                System.out.println("Scelta non valida. Operazione annullata.");
                return;
        }
        
        // Conferma finale
        String actionInfo = requiresExam ? "RICHIEDERE ESAMI (" + examType + ")" : 
                            (isApproved ? "APPROVARE" : "RIFIUTARE");
                            
        System.out.println("Stai per " + actionInfo + " il paziente.");
        System.out.print("Confermi operazione? [Y/n]: ");
        String confirm = scanner.nextLine().trim();
        
        if (confirm.equalsIgnoreCase("n")) {
            System.out.println("Operazione annullata. Il task rimane in attesa.");
            return;
        }

        // Esegui task
        Map<String, Object> completeVariables = new HashMap<>();
        
        completeVariables.put("isApproved", isApproved);
        completeVariables.put("requiresExam", requiresExam);
        if (requiresExam) {
            completeVariables.put("examType", examType);
            completeVariables.put("requesterRole", candidateGroup); // usato da Gateway_1mpo32s per redirigere dopo gli esami
            completeVariables.put("requesterName", currentUser.getNome() + " " + currentUser.getCognome());
        }
        completeVariables.put("note", note);
        
        completeVariables.put("validatorId", currentUser.getId());
        completeVariables.put("validatorRole", currentUser.getRuolo());

        System.out.print("Invio esito... ");
        restClient.assignTask(task.userTaskKey, currentUser.getId());
        restClient.completeTask(task.userTaskKey, completeVariables);
        System.out.println(colorize("COMPLETATO", Color.GREEN));
    }

    private void printHeader() {
        System.out.println(colorize("╔════════════════════════════════════════════════╗", Color.CYAN));
        System.out.println(colorize("║              PDTA - SPECIALIST CLI             ║", Color.CYAN));
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
