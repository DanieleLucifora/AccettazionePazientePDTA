package it.uni.pdta.pdta_camunda.cli;

import it.uni.pdta.pdta_camunda.model.OperatoreSanitario;
import it.uni.pdta.pdta_camunda.model.TecnicoLaboratorio;
import org.springframework.web.client.HttpClientErrorException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * CLI Diagnostica (Laboratorio / Radiologia)
 *
 * Comportamento:
 * 1. Polling automatico per task "Esami Diagnostici" (Activity_1nqhbxs)
 * 2. Visualizzazione richiesta esame (tipo esame)
 * 3. Inserimento referto
 * 4. Completamento task -> restituisce "examResult"
 */
public class DiagnosticCli {

    private static final int POLL_INTERVAL_MS = 5000;
    private final CamundaRestClient restClient;
    private final Scanner scanner;
    private final Set<String> handledTaskKeys;
    // Operatore generico di laboratorio
    private final OperatoreSanitario currentUser;

    public DiagnosticCli() {
        this.restClient = new CamundaRestClient(CliEndpoints.restV2BaseUrl());
        this.scanner = new Scanner(System.in);
        this.handledTaskKeys = new HashSet<>();
        // Simuliamo un utente generico di laboratorio, usando la classe concreta
        this.currentUser = new TecnicoLaboratorio("LAB001", "Mario", "Neri", "Laboratorio Analisi");
    }

    public static void main(String[] args) throws Exception {
        DiagnosticCli cli = new DiagnosticCli();
        cli.run(args);
    }

    public void run(String... args) throws Exception {
        printHeader();

        System.out.println("Benvenuto, " + currentUser.getNome() + " " + currentUser.getCognome());
        System.out.println("Reparto: " + currentUser.getUnitaOperativa());
        System.out.println();

        System.out.println(colorize(">>> Monitoraggio richieste esami in corso...", Color.CYAN));
        System.out.println(colorize("(Premere Ctrl+C per uscire)", Color.YELLOW));
        System.out.println();

        boolean taskFound = false;

        while (true) {
            try {
                // Cerca task generics
                Map<String, Object> searchFilter = new HashMap<>();
                
                List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(searchFilter);
                
                // Filtro per Task Definition ID "Activity_1nqhbxs" (Esami Diagnostici)
                CamundaRestClient.UserTask targetTask = null;
                if (tasks != null) {
                    for (CamundaRestClient.UserTask t : tasks) {
                        // Filtro per Candidate Group "LABORATORIO"
                        boolean isCreated = "CREATED".equalsIgnoreCase(t.state);
                        boolean hasGroup = t.candidateGroups != null && t.candidateGroups.contains("LABORATORIO");
                        boolean alreadyHandled = handledTaskKeys.contains(t.userTaskKey);
                        
                        if (isCreated && hasGroup && !alreadyHandled) {
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

                    // Chiedi se continuare
                    System.out.print("\nVuoi continuare a monitorare? [Y/n]: ");
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
                        System.out.print("\r" + colorize("In attesa di richieste esami... (polling ogni " +
                                (POLL_INTERVAL_MS / 1000) + "s)", Color.YELLOW) + "   ");
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }

            } catch (InterruptedException e) {
                System.out.println("\n\n⚠ Monitoraggio interrotto.");
                break;
            } catch (Exception e) {
                System.err.println("\n✗ Errore: " + e.getMessage());
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        scanner.close();
    }

    private void processTask(CamundaRestClient.UserTask task) throws Exception {
        System.out.println("\r" + colorize("✓ Nuova richiesta esame trovata!", Color.GREEN) + "                    ");
        System.out.println();

        System.out.println(colorize("=== RICHIESTA ESAME ===", Color.BLUE));
        
        // Coda richieste: priorita ONCOLOGI poi CHIRURGHI
        boolean oncologoPending = asBoolean(task.variables.get("oncologoRequiresExam"));
        boolean chirurgoPending = asBoolean(task.variables.get("chirurgoRequiresExam"));

        String requesterRole;
        String requesterName;
        String examType;

        if (oncologoPending) {
            requesterRole = "ONCOLOGI";
            requesterName = (String) task.variables.getOrDefault("oncologoRequesterName", "N/A");
            examType = (String) task.variables.getOrDefault("oncologoExamType", "Generico");
        } else if (chirurgoPending) {
            requesterRole = "CHIRURGHI";
            requesterName = (String) task.variables.getOrDefault("chirurgoRequesterName", "N/A");
            examType = (String) task.variables.getOrDefault("chirurgoExamType", "Generico");
        } else {
            System.out.println(colorize("Nessuna richiesta esami pendente: task obsoleto, skip.", Color.YELLOW));
            return;
        }

        // Recupera variabili contesto paziente
        String patientID = (String) task.variables.getOrDefault("patientID", "N/A");
        String patientData = (String) task.variables.getOrDefault("patientData", "N/A");
        String diagnosiCode = (String) task.variables.getOrDefault("diagnosiCode", "N/A");

        System.out.println("Paziente: " + patientData + " (ID: " + patientID + ")");
        System.out.println("Diagnosi: " + diagnosiCode);
        System.out.println("Richiesto da: " + colorize(requesterName, Color.YELLOW));
        System.out.println("TIPO ESAME RICHIESTO: " + colorize(examType, Color.RED));
        System.out.println();

        System.out.println("Esecuzione esame in corso...");
        System.out.print("Inserisci il referto dell'esame: ");
        String examResult = scanner.nextLine().trim();

        if (examResult.isEmpty()) {
            examResult = "Nessuna anomalia riscontrata.";
            System.out.println("(Referto vuoto -> inserito default: '" + examResult + "')");
        }

        // Conferma
        System.out.println("\nReferto da inviare:");
        System.out.println(colorize(examResult, Color.YELLOW));
        System.out.print("Confermi invio? [Y/n]: ");
        String confirm = scanner.nextLine().trim();
        
        if (confirm.equalsIgnoreCase("n")) {
            System.out.println("Operazione annullata. Il task rimane in attesa.");
            return;
        }

        // Completa task
        Map<String, Object> completeVariables = new HashMap<>();
        completeVariables.put("examResult", examResult);
        completeVariables.put("requesterRole", requesterRole);

        if ("ONCOLOGI".equals(requesterRole)) {
            completeVariables.put("oncologoRequiresExam", false);
            completeVariables.put("oncologoExamResult", examResult);
        } else {
            completeVariables.put("chirurgoRequiresExam", false);
            completeVariables.put("chirurgoExamResult", examResult);
        }

        System.out.print("Invio referto... ");
        try {
            restClient.assignTask(task.userTaskKey, currentUser.getId());
            restClient.completeTask(task.userTaskKey, completeVariables);
            handledTaskKeys.add(task.userTaskKey);
            System.out.println(colorize("COMPLETATO", Color.GREEN));
        } catch (HttpClientErrorException.NotFound e) {
            handledTaskKeys.add(task.userTaskKey);
            System.out.println(colorize("GIA' CHIUSO (sincronizzazione tasklist in ritardo)", Color.YELLOW));
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    private void printHeader() {
        System.out.println(colorize("╔════════════════════════════════════════════════╗", Color.CYAN));
        System.out.println(colorize("║              PDTA - DIAGNOSTIC CLI             ║", Color.CYAN));
        System.out.println(colorize("╚════════════════════════════════════════════════╝", Color.CYAN));
        System.out.println();
    }

    // Utility colori
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
