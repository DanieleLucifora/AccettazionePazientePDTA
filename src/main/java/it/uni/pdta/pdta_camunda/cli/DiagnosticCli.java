package it.uni.pdta.pdta_camunda.cli;

import it.uni.pdta.pdta_camunda.model.OperatoreSanitario;
import it.uni.pdta.pdta_camunda.model.TecnicoLaboratorio;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.BookingSlot;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import it.uni.pdta.pdta_camunda.scheduling.service.SlotConflictException;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private static final String ROLE_ONCOLOGI = "ONCOLOGI";
    private static final String ROLE_CHIRURGHI = "CHIRURGHI";
    private static final String NA = "N/A";
    private final CamundaRestClient restClient;
    private final Scanner scanner;
    private final Set<String> handledTaskKeys;
    // Operatore generico di laboratorio
    private final OperatoreSanitario currentUser;
    private final BookingService bookingService;
    private final ResourceEventService resourceEventService;

    public DiagnosticCli() {
        this.restClient = new CamundaRestClient(CliEndpoints.restV2BaseUrl());
        this.scanner = new Scanner(System.in);
        this.handledTaskKeys = new HashSet<>();
        // Simuliamo un utente generico di laboratorio, usando la classe concreta
        this.currentUser = new TecnicoLaboratorio("LAB001", "Mario", "Neri", "Laboratorio Analisi");
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

        boolean oncologoPending = asBoolean(task.variables.get("oncologoRequiresExam"));
        boolean chirurgoPending = asBoolean(task.variables.get("chirurgoRequiresExam"));

        String examQueue = normalizeQueue(task.variables.get("examQueue"));
        String requesterRole = pickNextRequester(examQueue, oncologoPending, chirurgoPending);
        if (!ROLE_ONCOLOGI.equals(requesterRole) && !ROLE_CHIRURGHI.equals(requesterRole)) {
            System.out.println(colorize("Nessuna richiesta esami pendente: task obsoleto, skip.", Color.YELLOW));
            return;
        }

        String requesterName = resolveRequesterName(task.variables, requesterRole);
        String requesterId = resolveRequesterId(task.variables, requesterRole);
        String examType = resolveRequesterExamType(task.variables, requesterRole);

        // Recupera variabili contesto paziente
        String patientID = (String) task.variables.getOrDefault("patientID", NA);
        String patientData = (String) task.variables.getOrDefault("patientData", NA);
        String diagnosiCode = (String) task.variables.getOrDefault("diagnosiCode", NA);
        String bodyPartRaw = resolveRoleAwareVar(task.variables, requesterRole, "BodyPart", "bodyPart", "TOTAL_BODY");
        String schedulingPreference = resolveRoleAwareVar(task.variables,
                requesterRole,
                "SchedulingPreference",
                "schedulingPreference",
                "AUTO_FIRST_AVAILABLE");
        String preferredExamDate = resolveRoleAwareVar(task.variables,
                requesterRole,
                "PreferredExamDate",
                "preferredExamDate",
                "");
        SpecialistSelection specialistSelection = readSpecialistSelection(task.variables, requesterRole);

        ExamType requestedExamType = parseExamType(examType);
        BodyPart requestedBodyPart = parseBodyPart(bodyPartRaw);
        BookingDecision bookingDecision = null;

        System.out.println("Paziente: " + patientData + " (ID: " + patientID + ")");
        System.out.println("Diagnosi: " + diagnosiCode);
        System.out.println("Richiesto da: " + colorize(requesterName, Color.YELLOW));
        System.out.println("TIPO ESAME RICHIESTO: " + colorize(examType, Color.RED));
        System.out.println("DISTRETTO: " + colorize(bodyPartRaw, Color.CYAN));
        if (!preferredExamDate.isBlank()) {
            System.out.println("Data preferita specialista: " + colorize(preferredExamDate, Color.CYAN));
        }
        if (specialistSelection.hasSelection()) {
            System.out.println("Selezione specialista: " + colorize(specialistSelection.toDisplay(), Color.CYAN));
        }
        if (bookingService != null && requestedExamType != null && requestedBodyPart != null) {
            bookingDecision = scheduleSlot(
                    task,
                    requesterRole,
                    patientID,
                    schedulingPreference,
                    preferredExamDate,
                    requestedExamType,
                    requestedBodyPart,
                    specialistSelection
            );
        }
        System.out.println();

        if (bookingDecision != null && bookingDecision.rejected) {
            String rejectionResult = "Esame rifiutato dalla diagnostica: " + bookingDecision.reason;
            logRejectedEvent(
                    task,
                    bookingDecision,
                    requesterRole,
                        requesterId,
                    requesterName
            );
            completeTaskWithOutcome(task, requesterRole, examQueue, rejectionResult, bookingDecision);
            return;
        }

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

        logPostDecisionEvents(
            task,
            bookingDecision,
            examResult,
            requesterRole,
            requesterId,
            requesterName
        );

        completeTaskWithOutcome(task, requesterRole, examQueue, examResult, bookingDecision);
    }

    private void logPostDecisionEvents(CamundaRestClient.UserTask task,
                                       BookingDecision bookingDecision,
                                       String examResult,
                                       String requesterRole,
                                       String requesterId,
                                       String requesterName) {
        if (resourceEventService == null || bookingDecision == null || bookingDecision.slot == null) {
            return;
        }

        Map<String, Object> auditMetadata = buildAuditMetadata(requesterRole, requesterId, requesterName);

        if (bookingDecision.modified) {
            resourceEventService.logSlotRescheduled(
                    bookingDecision.slot,
                    currentUser.getId(),
                    task.processInstanceKey,
                    bookingDecision.reason,
                    auditMetadata
            );
        }

        resourceEventService.logExamCompleted(
                bookingDecision.slot,
                currentUser.getId(),
                task.processInstanceKey,
                examResult,
                auditMetadata
        );
    }

    private void logRejectedEvent(CamundaRestClient.UserTask task,
                                  BookingDecision bookingDecision,
                                  String requesterRole,
                                  String requesterId,
                                  String requesterName) {
        if (resourceEventService == null || bookingDecision == null || bookingDecision.proposed == null) {
            return;
        }
        resourceEventService.logExamCanceled(
                bookingDecision.proposed.getResourceId(),
                null,
                bookingDecision.proposed.getProtocolId(),
                currentUser.getId(),
                task.processInstanceKey,
                bookingDecision.reason,
                buildAuditMetadata(requesterRole, requesterId, requesterName)
        );
    }

    private void completeTaskWithOutcome(CamundaRestClient.UserTask task,
                                         String requesterRole,
                                         String examQueue,
                                         String examResult,
                                         BookingDecision bookingDecision) throws Exception {
        // Completa task
        Map<String, Object> completeVariables = new HashMap<>();
        completeVariables.put("examResult", examResult);
        completeVariables.put("requesterRole", requesterRole);
        completeVariables.put("examQueue", dequeueRequester(examQueue, requesterRole));

        if (bookingDecision != null && bookingDecision.slot != null) {
            BookingSlot bookedSlot = bookingDecision.slot;
            completeVariables.put("bookedSlotId", String.valueOf(bookedSlot.getId()));
            completeVariables.put("bookedSlotStart", String.valueOf(bookedSlot.getStartTime()));
            completeVariables.put("bookedSlotEnd", String.valueOf(bookedSlot.getEndTime()));
            completeVariables.put("bookedResourceCode", bookedSlot.getResource().getCode());
            completeVariables.put("diagnosticReporterId", currentUser.getId());
            completeVariables.put("diagnosticReporterName", currentUser.getNome() + " " + currentUser.getCognome());
            completeVariables.put("diagnosticReporterRole", currentUser.getRuolo());
            completeVariables.put("appointmentModified", bookingDecision.modified);
            completeVariables.put("examModified", bookingDecision.modified);
            if (bookingDecision.modified && bookingDecision.reason != null) {
                completeVariables.put("appointmentModificationReason", bookingDecision.reason);
                completeVariables.put("examModificationReason", bookingDecision.reason);
            }
        }

        completeVariables.put("examRejected", bookingDecision != null && bookingDecision.rejected);
        if (bookingDecision != null && bookingDecision.rejected && bookingDecision.reason != null) {
            completeVariables.put("examRejectionReason", bookingDecision.reason);
        }

        if (ROLE_ONCOLOGI.equals(requesterRole)) {
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

    private BookingDecision scheduleSlot(CamundaRestClient.UserTask task,
                                         String requesterRole,
                                         String patientId,
                                         String schedulingPreference,
                                         String preferredExamDate,
                                         ExamType examType,
                                         BodyPart bodyPart,
                                         SpecialistSelection specialistSelection) {
        System.out.println();
        System.out.println(colorize("--- Scheduling slot diagnostico ---", Color.BLUE));

        SlotSuggestion proposed = buildProposedSlot(schedulingPreference, preferredExamDate, examType, bodyPart, specialistSelection);
        if (proposed == null) {
            System.out.println(colorize("Nessuna proposta prenotabile trovata.", Color.YELLOW));
            return null;
        }

        System.out.println("Proposta esame:");
        System.out.println(colorize(proposed.toCliDisplay(), Color.CYAN));
        System.out.println("Azioni:");
        System.out.println("1. Conferma esame proposto");
        System.out.println("2. Modifica data/ora esame (richiede giustificazione)");
        System.out.println("3. Rifiuta esame (richiede giustificazione)");
        System.out.print("Scelta [1-3]: ");
        String action = scanner.nextLine().trim();

        SlotSuggestion selected = proposed;
        boolean modified = false;
        boolean rejected = false;
        String reason = null;

        if ("3".equals(action)) {
            reason = askRejectionReason();
            if (reason == null) {
                System.out.println(colorize("Rifiuto esame annullato: motivazione obbligatoria.", Color.RED));
                return null;
            }
            rejected = true;
            return new BookingDecision(null, proposed, false, true, reason);
        }

        if ("2".equals(action)) {
            reason = askModificationReason();
            if (reason == null) {
                System.out.println(colorize("Modifica esame annullata: giustificazione obbligatoria.", Color.RED));
                return null;
            }

            SlotSuggestion manualSelection = chooseManualSlotForDiagnostic(examType, bodyPart);
            if (manualSelection == null) {
                System.out.println(colorize("Modifica annullata: nessuna fascia selezionata.", Color.RED));
                return null;
            }
            selected = manualSelection;
            modified = true;
        }

        try {
            BookingSlot slot = bookingService.bookSlot(
                    selected.getResourceId(),
                    selected.getProtocolId(),
                    selected.getStartTime(),
                    parseProcessInstanceKey(task.processInstanceKey),
                    patientId,
                    requesterRole,
                    buildAuditMetadata(
                            requesterRole,
                            resolveRequesterId(task.variables, requesterRole),
                            resolveRequesterName(task.variables, requesterRole)
                    )
            );
            System.out.println(colorize("Esame pianificato: " + selected.toCliDisplay(), Color.GREEN));
            return new BookingDecision(slot, selected, modified, rejected, reason);
        } catch (SlotConflictException e) {
            System.out.println(colorize("Conflitto prenotazione: " + e.getMessage(), Color.RED));
            return null;
        }
    }

    private SlotSuggestion buildProposedSlot(String mode,
                                             String preferredExamDate,
                                             ExamType examType,
                                             BodyPart bodyPart,
                                             SpecialistSelection specialistSelection) {
        if (specialistSelection != null && specialistSelection.hasSelection()) {
            SlotSuggestion selected = specialistSelection.toSlotSuggestion();
            if (selected != null) {
                return selected;
            }
        }

        if ("MANUAL_CALENDAR".equals(mode)) {
            LocalDate preferred = parseLocalDateFlexible(preferredExamDate);
            LocalDate day = preferred != null ? preferred : LocalDate.now();
            List<SlotSuggestion> daySlots = bookingService.getAvailableSlotsForDay(examType, bodyPart, day);
            if (daySlots.isEmpty() && preferred != null) {
                daySlots = bookingService.getAvailableSlotsForDay(examType, bodyPart, LocalDate.now());
            }
            return daySlots.isEmpty() ? null : daySlots.getFirst();
        }
        return bookingService.findFirstAvailableSlot(examType, bodyPart, LocalDate.now()).orElse(null);
    }

    private String askModificationReason() {
        System.out.print("Giustificazione modifica esame (obbligatoria): ");
        String reason = scanner.nextLine().trim();
        return reason.isEmpty() ? null : reason;
    }

    private String askRejectionReason() {
        System.out.print("Motivazione rifiuto esame (obbligatoria): ");
        String reason = scanner.nextLine().trim();
        return reason.isEmpty() ? null : reason;
    }

    private SlotSuggestion chooseManualSlotForDiagnostic(ExamType examType, BodyPart bodyPart) {
        LocalDate baseDate = LocalDate.now();
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            days.add(baseDate.plusDays(i));
        }

        System.out.println("Seleziona nuovo giorno esame (prossimi 10 giorni):");
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
            return null;
        }
        if (dayIndex < 0 || dayIndex >= days.size()) {
            return null;
        }

        LocalDate selectedDay = days.get(dayIndex);
        List<SlotSuggestion> slots = bookingService.getAvailableSlotsForDay(examType, bodyPart, selectedDay);
        if (slots.isEmpty()) {
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
                return slots.get(slotIndex);
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        return null;
    }

    private LocalDate parseLocalDateFlexible(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("d/M/yyyy")
        );
        for (DateTimeFormatter fmt : formats) {
            try {
                return LocalDate.parse(raw.trim(), fmt);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private String readStringVar(Map<String, Object> vars, String key, String defaultValue) {
        Object value = vars.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private ExamType parseExamType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ExamType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            System.out.println(colorize("ExamType non riconosciuto: " + raw, Color.YELLOW));
            return null;
        }
    }

    private BodyPart parseBodyPart(String raw) {
        if (raw == null || raw.isBlank()) {
            return BodyPart.TOTAL_BODY;
        }
        try {
            return BodyPart.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            System.out.println(colorize("BodyPart non riconosciuto: " + raw + " (default TOTAL_BODY)", Color.YELLOW));
            return BodyPart.TOTAL_BODY;
        }
    }

    private Long parseProcessInstanceKey(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private SpecialistSelection readSpecialistSelection(Map<String, Object> vars, String requesterRole) {
        if (ROLE_ONCOLOGI.equals(requesterRole)) {
            String start = readStringVar(vars, "oncologoSelectedSlotStart", readStringVar(vars, "specialistSelectedSlotStart", ""));
            String end = readStringVar(vars, "oncologoSelectedSlotEnd", readStringVar(vars, "specialistSelectedSlotEnd", ""));
            String resourceId = readStringVar(vars, "oncologoSelectedResourceId", readStringVar(vars, "specialistSelectedResourceId", ""));
            String protocolId = readStringVar(vars, "oncologoSelectedProtocolId", readStringVar(vars, "specialistSelectedProtocolId", ""));
            String resourceCode = readStringVar(vars, "oncologoSelectedResourceCode", readStringVar(vars, "specialistSelectedResourceCode", ""));
            return new SpecialistSelection(start, end, resourceId, protocolId, resourceCode);
        }

        String start = readStringVar(vars, "chirurgoSelectedSlotStart", readStringVar(vars, "specialistSelectedSlotStart", ""));
        String end = readStringVar(vars, "chirurgoSelectedSlotEnd", readStringVar(vars, "specialistSelectedSlotEnd", ""));
        String resourceId = readStringVar(vars, "chirurgoSelectedResourceId", readStringVar(vars, "specialistSelectedResourceId", ""));
        String protocolId = readStringVar(vars, "chirurgoSelectedProtocolId", readStringVar(vars, "specialistSelectedProtocolId", ""));
        String resourceCode = readStringVar(vars, "chirurgoSelectedResourceCode", readStringVar(vars, "specialistSelectedResourceCode", ""));
        return new SpecialistSelection(start, end, resourceId, protocolId, resourceCode);
    }

    private static class BookingDecision {
        private final BookingSlot slot;
        private final SlotSuggestion proposed;
        private final boolean modified;
        private final boolean rejected;
        private final String reason;

        private BookingDecision(BookingSlot slot,
                                SlotSuggestion proposed,
                                boolean modified,
                                boolean rejected,
                                String reason) {
            this.slot = slot;
            this.proposed = proposed;
            this.modified = modified;
            this.rejected = rejected;
            this.reason = reason;
        }
    }

    private static class SpecialistSelection {
        private final String start;
        private final String end;
        private final String resourceId;
        private final String protocolId;
        private final String resourceCode;

        private SpecialistSelection(String start, String end, String resourceId, String protocolId, String resourceCode) {
            this.start = start;
            this.end = end;
            this.resourceId = resourceId;
            this.protocolId = protocolId;
            this.resourceCode = resourceCode;
        }

        private boolean hasSelection() {
            return !start.isBlank() && !resourceId.isBlank() && !protocolId.isBlank();
        }

        private String toDisplay() {
            return start + " -> " + end + " (risorsa " + resourceCode + ")";
        }

        private SlotSuggestion toSlotSuggestion() {
            try {
                Long resId = Long.parseLong(resourceId);
                Long protId = Long.parseLong(protocolId);
                LocalDateTime s = LocalDateTime.parse(start);
                LocalDateTime e = end.isBlank() ? s.plusMinutes(30) : LocalDateTime.parse(end);

                it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource res =
                        new it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource();
                res.setId(resId);
                res.setCode(resourceCode.isBlank() ? NA : resourceCode);
                res.setName("SelezioneSpecialista");

                it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol prot =
                        new it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol();
                prot.setId(protId);
                prot.setDisplayName("EsameSelezionato");
                prot.setTotalSlotMinutes((int) java.time.Duration.between(s, e).toMinutes());
                prot.setRequiresContrast(false);
                prot.setRequiresFasting(false);

                return new SlotSuggestion(res, prot, s, e);
            } catch (Exception ex) {
                return null;
            }
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

    private String pickNextRequester(String queue, boolean oncologoPending, boolean chirurgoPending) {
        if (queue != null && !queue.isEmpty()) {
            String[] tokens = queue.split(",");
            for (String token : tokens) {
                String role = token.trim();
                if (ROLE_ONCOLOGI.equals(role) && oncologoPending) {
                    return role;
                }
                if (ROLE_CHIRURGHI.equals(role) && chirurgoPending) {
                    return role;
                }
            }
        }

        if (oncologoPending) {
            return ROLE_ONCOLOGI;
        }
        if (chirurgoPending) {
            return ROLE_CHIRURGHI;
        }
        return "";
    }

    private String dequeueRequester(String queue, String requesterRole) {
        if (queue == null || queue.isEmpty()) {
            return "";
        }

        String[] tokens = queue.split(",");
        StringBuilder builder = new StringBuilder();
        boolean removed = false;

        for (String token : tokens) {
            String role = token.trim();
            if (role.isEmpty()) {
                continue;
            }
            if (!removed && role.equals(requesterRole)) {
                removed = true;
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(role);
        }

        return builder.toString();
    }

    private String normalizeQueue(Object value) {
        if (value == null) {
            return "";
        }

        String queue = value.toString().trim();
        if (queue.isEmpty()) {
            return "";
        }

        String[] tokens = queue.split(",");
        StringBuilder builder = new StringBuilder();

        for (String token : tokens) {
            String role = token.trim();
            if (!ROLE_ONCOLOGI.equals(role) && !ROLE_CHIRURGHI.equals(role)) {
                continue;
            }
            if (builder.indexOf(role) >= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(role);
        }

        return builder.toString();
    }

    private Map<String, Object> buildAuditMetadata(String requesterRole, String requesterId, String requesterName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestedByRole", requesterRole);
        payload.put("requestedById", requesterId);
        payload.put("requestedByName", requesterName);
        payload.put("reportedById", currentUser.getId());
        payload.put("reportedByName", currentUser.getNome() + " " + currentUser.getCognome());
        payload.put("reportedByRole", currentUser.getRuolo());
        return payload;
    }

    private String resolveRequesterId(Map<String, Object> variables, String requesterRole) {
        if (ROLE_ONCOLOGI.equals(requesterRole)) {
            return readStringVar(variables, "oncologoRequesterId", readStringVar(variables, "requesterId", NA));
        }
        if (ROLE_CHIRURGHI.equals(requesterRole)) {
            return readStringVar(variables, "chirurgoRequesterId", readStringVar(variables, "requesterId", NA));
        }
        return readStringVar(variables, "requesterId", NA);
    }

    private String resolveRequesterName(Map<String, Object> variables, String requesterRole) {
        if (ROLE_ONCOLOGI.equals(requesterRole)) {
            return readStringVar(variables, "oncologoRequesterName", readStringVar(variables, "requesterName", NA));
        }
        if (ROLE_CHIRURGHI.equals(requesterRole)) {
            return readStringVar(variables, "chirurgoRequesterName", readStringVar(variables, "requesterName", NA));
        }
        return readStringVar(variables, "requesterName", NA);
    }

    private String resolveRequesterExamType(Map<String, Object> variables, String requesterRole) {
        if (ROLE_ONCOLOGI.equals(requesterRole)) {
            return readStringVar(variables, "oncologoExamType", "Generico");
        }
        if (ROLE_CHIRURGHI.equals(requesterRole)) {
            return readStringVar(variables, "chirurgoExamType", "Generico");
        }
        return "Generico";
    }

    private String resolveRoleAwareVar(Map<String, Object> vars,
                                       String requesterRole,
                                       String roleSuffix,
                                       String genericKey,
                                       String defaultValue) {
        String roleKey = ROLE_ONCOLOGI.equals(requesterRole)
                ? "oncologo" + roleSuffix
                : "chirurgo" + roleSuffix;
        return readStringVar(vars, roleKey, readStringVar(vars, genericKey, defaultValue));
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
