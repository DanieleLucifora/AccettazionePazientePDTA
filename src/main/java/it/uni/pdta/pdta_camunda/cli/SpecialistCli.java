package it.uni.pdta.pdta_camunda.cli;

import it.uni.pdta.pdta_camunda.model.Chirurgo;
import it.uni.pdta.pdta_camunda.model.Oncologo;
import it.uni.pdta.pdta_camunda.model.OperatoreSanitario;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

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
    private static final String ROLE_CHIRURGHI = "CHIRURGHI";
    private static final String ROLE_ONCOLOGI = "ONCOLOGI";
    private static final String NA = "N/A";
    private final CamundaRestClient restClient;
    private final Scanner scanner;
    private final BookingService bookingService;
    private final ResourceEventService resourceEventService;
    private OperatoreSanitario currentUser;
    private String candidateGroup;

    public SpecialistCli() {
        this.restClient = new CamundaRestClient(CliEndpoints.restV2BaseUrl());
        this.scanner = new Scanner(System.in);
        BookingService service;
        ResourceEventService eventService;
        try {
            service = SchedulingCliSupport.getBookingService();
            eventService = SchedulingCliSupport.getResourceEventService();
        } catch (Exception e) {
            service = null;
            eventService = null;
            System.err.println("[WARN] Scheduling service non disponibile: " + e.getMessage());
        }
        this.bookingService = service;
        this.resourceEventService = eventService;
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
            this.candidateGroup = ROLE_CHIRURGHI;
        } else if (choice.equals("2")) {
            this.currentUser = new Oncologo("ON001", "Luigi", "Rossi", "Oncologia Medica");
            this.candidateGroup = ROLE_ONCOLOGI;
        } else {
            System.out.println("Scelta non valida. Default: Chirurgo.");
            this.currentUser = new Chirurgo("CH001", "Marco", "Bianchi", "Chirurgia Generale", "Toracica");
            this.candidateGroup = ROLE_CHIRURGHI;
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
        String patientID = (String) task.variables.getOrDefault("patientID", NA);
        String patientData = (String) task.variables.getOrDefault("patientData", NA);
        String diagnosiCode = (String) task.variables.getOrDefault("diagnosiCode", NA);
        String clinicalFileUrl = (String) task.variables.getOrDefault("clinicalFileUrl", NA);
        // Mostra il referto relativo al ruolo corrente; fallback su examResult per compatibilita
        String roleSpecificExamResult = ROLE_CHIRURGHI.equals(candidateGroup)
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
        String bodyPart = "";
        String schedulingPreference = "AUTO_FIRST_AVAILABLE";
        String preferredExamDate = "";
        SlotSuggestion selectedManualSlot = null;
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
                ExamType selectedExamType = chooseExamType();
                BodyPart selectedBodyPart = chooseBodyPart();
                schedulingPreference = chooseSchedulingPreference();

                examType = selectedExamType.name();
                bodyPart = selectedBodyPart.name();
                if ("MANUAL_CALENDAR".equals(schedulingPreference)) {
                    selectedManualSlot = chooseManualSlot(selectedExamType, selectedBodyPart);
                    if (selectedManualSlot == null) {
                        System.out.println("Nessuna fascia selezionata. Operazione annullata.");
                        return;
                    }
                    preferredExamDate = selectedManualSlot.getStartTime().toLocalDate().toString();

                    if (resourceEventService != null) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("requestedExamType", selectedExamType.name());
                        payload.put("requestedBodyPart", selectedBodyPart.name());
                        payload.put("requestedStart", selectedManualSlot.getStartTime().toString());
                        payload.put("requestedEnd", selectedManualSlot.getEndTime().toString());
                        payload.put("requestedById", currentUser.getId());
                        payload.put("requestedByName", currentUser.getNome() + " " + currentUser.getCognome());
                        payload.put("requestedByRole", currentUser.getRuolo());

                        resourceEventService.logEvent(
                                selectedManualSlot.getResourceId(),
                                null,
                                selectedManualSlot.getProtocolId(),
                                ResourceEventType.REQUEST_CREATED,
                                currentUser.getId(),
                                task.processInstanceKey,
                                payload
                        );
                    }
                }

                showSchedulingPreview(selectedExamType, selectedBodyPart, preferredExamDate);

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
            completeVariables.put("bodyPart", bodyPart);
            completeVariables.put("schedulingPreference", schedulingPreference);
            if (!preferredExamDate.isBlank()) {
                completeVariables.put("preferredExamDate", preferredExamDate);
            }
            if (selectedManualSlot != null) {
                completeVariables.put("specialistSelectedSlotStart", selectedManualSlot.getStartTime().toString());
                completeVariables.put("specialistSelectedSlotEnd", selectedManualSlot.getEndTime().toString());
                completeVariables.put("specialistSelectedResourceId", String.valueOf(selectedManualSlot.getResourceId()));
                completeVariables.put("specialistSelectedProtocolId", String.valueOf(selectedManualSlot.getProtocolId()));
                completeVariables.put("specialistSelectedResourceCode", selectedManualSlot.getResourceCode());
            }
            completeVariables.put("requesterName", currentUser.getNome() + " " + currentUser.getCognome());
            completeVariables.put("requesterId", currentUser.getId());
            completeVariables.put("examQueue", enqueueRequester(task.variables.get("examQueue"), candidateGroup));

            if (ROLE_ONCOLOGI.equals(candidateGroup)) {
                completeVariables.put("oncologoExamType", examType);
                completeVariables.put("oncologoBodyPart", bodyPart);
                completeVariables.put("oncologoRequesterName", currentUser.getNome() + " " + currentUser.getCognome());
                completeVariables.put("oncologoRequesterId", currentUser.getId());
                completeVariables.put("oncologoSchedulingPreference", schedulingPreference);
                if (!preferredExamDate.isBlank()) {
                    completeVariables.put("oncologoPreferredExamDate", preferredExamDate);
                }
                if (selectedManualSlot != null) {
                    completeVariables.put("oncologoSelectedSlotStart", selectedManualSlot.getStartTime().toString());
                    completeVariables.put("oncologoSelectedSlotEnd", selectedManualSlot.getEndTime().toString());
                    completeVariables.put("oncologoSelectedResourceId", String.valueOf(selectedManualSlot.getResourceId()));
                    completeVariables.put("oncologoSelectedProtocolId", String.valueOf(selectedManualSlot.getProtocolId()));
                    completeVariables.put("oncologoSelectedResourceCode", selectedManualSlot.getResourceCode());
                }
            } else if (ROLE_CHIRURGHI.equals(candidateGroup)) {
                completeVariables.put("chirurgoExamType", examType);
                completeVariables.put("chirurgoBodyPart", bodyPart);
                completeVariables.put("chirurgoRequesterName", currentUser.getNome() + " " + currentUser.getCognome());
                completeVariables.put("chirurgoRequesterId", currentUser.getId());
                completeVariables.put("chirurgoSchedulingPreference", schedulingPreference);
                if (!preferredExamDate.isBlank()) {
                    completeVariables.put("chirurgoPreferredExamDate", preferredExamDate);
                }
                if (selectedManualSlot != null) {
                    completeVariables.put("chirurgoSelectedSlotStart", selectedManualSlot.getStartTime().toString());
                    completeVariables.put("chirurgoSelectedSlotEnd", selectedManualSlot.getEndTime().toString());
                    completeVariables.put("chirurgoSelectedResourceId", String.valueOf(selectedManualSlot.getResourceId()));
                    completeVariables.put("chirurgoSelectedProtocolId", String.valueOf(selectedManualSlot.getProtocolId()));
                    completeVariables.put("chirurgoSelectedResourceCode", selectedManualSlot.getResourceCode());
                }
            }
        }
        completeVariables.put("note", note);
        
        completeVariables.put("validatorId", currentUser.getId());
        completeVariables.put("validatorRole", currentUser.getRuolo());

        System.out.print("Invio esito... ");
        restClient.assignTask(task.userTaskKey, currentUser.getId());
        restClient.completeTask(task.userTaskKey, completeVariables);
        System.out.println(colorize("COMPLETATO", Color.GREEN));
    }

    private String enqueueRequester(Object currentQueue, String roleToAdd) {
        Set<String> roles = new LinkedHashSet<>();

        if (currentQueue != null) {
            String queueText = currentQueue.toString().trim();
            if (!queueText.isEmpty()) {
                String[] tokens = queueText.split(",");
                for (String token : tokens) {
                    String normalized = token.trim();
                    if (!normalized.isEmpty()) {
                        roles.add(normalized);
                    }
                }
            }
        }

        roles.add(roleToAdd);
        return String.join(",", roles);
    }

    private ExamType chooseExamType() {
        ExamType[] values = ExamType.values();
        System.out.println("Seleziona tipo esame:");
        for (int i = 0; i < values.length; i++) {
            System.out.println((i + 1) + ". " + values[i].name());
        }
        System.out.print("Scelta [1-" + values.length + "]: ");
        String raw = scanner.nextLine().trim();
        try {
            int idx = Integer.parseInt(raw);
            if (idx >= 1 && idx <= values.length) {
                return values[idx - 1];
            }
        } catch (NumberFormatException ignored) {
            // fallback
        }
        System.out.println("Scelta non valida. Default: TAC");
        return ExamType.TAC;
    }

    private BodyPart chooseBodyPart() {
        BodyPart[] values = BodyPart.values();
        System.out.println("Seleziona parte corporea:");
        for (int i = 0; i < values.length; i++) {
            System.out.println((i + 1) + ". " + values[i].name());
        }
        System.out.print("Scelta [1-" + values.length + "]: ");
        String raw = scanner.nextLine().trim();
        try {
            int idx = Integer.parseInt(raw);
            if (idx >= 1 && idx <= values.length) {
                return values[idx - 1];
            }
        } catch (NumberFormatException ignored) {
            // fallback
        }
        System.out.println("Scelta non valida. Default: TOTAL_BODY");
        return BodyPart.TOTAL_BODY;
    }

    private String chooseSchedulingPreference() {
        System.out.println("Preferenza scheduling:");
        System.out.println("1. Primo slot libero (automatico)");
        System.out.println("2. Calendario manuale (scelta giorno/slot)");
        System.out.print("Scelta [1-2]: ");
        String choice = scanner.nextLine().trim();
        if ("2".equals(choice)) {
            return "MANUAL_CALENDAR";
        }
        return "AUTO_FIRST_AVAILABLE";
    }

    private SlotSuggestion chooseManualSlot(ExamType examType, BodyPart bodyPart) {
        if (bookingService == null) {
            System.out.println("Scheduling non disponibile al momento.");
            return null;
        }

        LocalDate baseDate = LocalDate.now();
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            days.add(baseDate.plusDays(i));
        }

        System.out.println("Seleziona giorno esame (prossimi 10 giorni):");
        for (int i = 0; i < days.size(); i++) {
            LocalDate day = days.get(i);
            int count = bookingService.getAvailableSlotsForDay(examType, bodyPart, day).size();
            System.out.println((i + 1) + ". " + day + " (fasce disponibili: " + count + ")");
        }
        System.out.print("Scelta giorno [1-10]: ");
        String rawDayChoice = scanner.nextLine().trim();

        int dayIndex;
        try {
            dayIndex = Integer.parseInt(rawDayChoice) - 1;
        } catch (NumberFormatException ex) {
            System.out.println("Scelta non valida.");
            return null;
        }

        if (dayIndex < 0 || dayIndex >= days.size()) {
            System.out.println("Scelta non valida.");
            return null;
        }

        LocalDate selectedDay = days.get(dayIndex);
        List<SlotSuggestion> slots = bookingService.getAvailableSlotsForDay(examType, bodyPart, selectedDay);
        if (slots.isEmpty()) {
            System.out.println("Nessuna fascia disponibile nel giorno selezionato.");
            return null;
        }

        int max = Math.min(slots.size(), 20);
        System.out.println("Fasce orarie disponibili il " + selectedDay + ":");
        for (int i = 0; i < max; i++) {
            System.out.println((i + 1) + "." + slots.get(i).toCliDisplay());
        }
        System.out.print("Scelta fascia [1-" + max + "]: ");
        String rawSlotChoice = scanner.nextLine().trim();

        try {
            int slotIndex = Integer.parseInt(rawSlotChoice) - 1;
            if (slotIndex >= 0 && slotIndex < max) {
                SlotSuggestion selected = slots.get(slotIndex);
                System.out.println(colorize("Selezione effettuata: " + selected.toCliDisplay(), Color.GREEN));
                return selected;
            }
        } catch (NumberFormatException ignored) {
            // handled below
        }

        System.out.println("Scelta non valida.");
        return null;
    }

    private void showSchedulingPreview(ExamType examType, BodyPart bodyPart, String preferredExamDate) {
        if (bookingService == null) {
            return;
        }

        System.out.println();
        System.out.println(colorize("--- Preview disponibilita esame (informativa) ---", Color.BLUE));

        List<SlotSuggestion> suggestions = new ArrayList<>();
        LocalDate startDay = LocalDate.now();

        if (!preferredExamDate.isBlank()) {
            try {
                startDay = LocalDate.parse(preferredExamDate, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // fallback a today
            }
        }

        for (int i = 0; i < 10 && suggestions.size() < 5; i++) {
            LocalDate day = startDay.plusDays(i);
            List<SlotSuggestion> daySlots = bookingService.getAvailableSlotsForDay(examType, bodyPart, day);
            for (SlotSuggestion slot : daySlots) {
                suggestions.add(slot);
                if (suggestions.size() == 5) {
                    break;
                }
            }
        }

        if (suggestions.isEmpty()) {
            System.out.println(colorize("Nessuna disponibilita trovata nei prossimi giorni.", Color.YELLOW));
            return;
        }

        System.out.println("Primi slot disponibili:");
        for (int i = 0; i < suggestions.size(); i++) {
            System.out.println((i + 1) + "." + suggestions.get(i).toCliDisplay());
        }
        System.out.println(colorize("Nota: questa e' una preview. La conferma/modifica/rifiuto finale avviene in Diagnostica.", Color.CYAN));
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
