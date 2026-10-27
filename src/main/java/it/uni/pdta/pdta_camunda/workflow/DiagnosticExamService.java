package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ExamProtocolRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class DiagnosticExamService {
    private final SpecialistTaskService tasks;
    private final CamundaRestClient rest;
    private final BookingService booking;
    private final ExamProtocolRepository protocols;
    private final ResourceEventService events;

    public DiagnosticExamService(SpecialistTaskService tasks, CamundaRestClient rest, BookingService booking,
            ExamProtocolRepository protocols, ResourceEventService events) {
        this.tasks = tasks;
        this.rest = rest;
        this.booking = booking;
        this.protocols = protocols;
        this.events = events;
    }

    public ExamOptions options() {
        LocalDate today = LocalDate.now();
        List<ExamOption> available = protocols.findAll().stream()
                .filter(p -> p.isActive() && !p.getEffectiveFrom().isAfter(today))
                .map(p -> new ExamOption(p.getExamType(), p.getBodyPart()))
                .distinct().sorted(Comparator.comparing((ExamOption option) -> option.examType().name())
                        .thenComparing(option -> option.bodyPart().name())).toList();
        return new ExamOptions(available, today, today.plusDays(9));
    }

    public List<SlotSuggestion> slots(ExamType examType, BodyPart bodyPart, LocalDate day) {
        requireProtocol(examType, bodyPart);
        LocalDate today = LocalDate.now();
        if (day.isBefore(today) || day.isAfter(today.plusDays(9))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scegli un giorno nei prossimi 10 giorni.");
        }
        return booking.getAvailableSlotsForDay(examType, bodyPart, day).stream()
                .filter(slot -> slot.getStartTime().isAfter(LocalDateTime.now()))
                .sorted(Comparator.comparing(SlotSuggestion::getStartTime)
                        .thenComparing(SlotSuggestion::getResourceCode)).toList();
    }

    public SpecialistTaskService.TaskActionResult request(String taskKey, SpecialistTaskService.SpecialistRole role,
            DiagnosticExamRequest request) throws Exception {
        if (!request.isValidRequest()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Richiesta esame non valida.");
        }
        var task = tasks.requireOwnedTask(taskKey, role, role.operatorId());
        requireProtocol(request.examType(), request.bodyPart());
        SlotSuggestion selection = null;
        if (request.schedulingPreference() == DiagnosticExamRequest.SchedulingPreference.MANUAL_CALENDAR) {
            selection = slots(request.examType(), request.bodyPart(), request.startTime().toLocalDate()).stream()
                    .filter(slot -> slot.getResourceId().equals(request.resourceId())
                            && slot.getStartTime().equals(request.startTime()))
                    .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "La fascia selezionata non e' piu' disponibile. Aggiorna il calendario."));
        }
        String name = role == SpecialistTaskService.SpecialistRole.CHIRURGHI ? "Marco Bianchi" : "Luigi Rossi";
        var variables = rest.getTypedTaskVariables(taskKey);
        var result = tasks.completeOwnedTask(taskKey, role.operatorId(),
                request.variables(role, role.operatorId(), name, role.validatorRole(), variables.get("examQueue"), selection));
        if (selection != null) {
            events.logEvent(selection.getResourceId(), null, selection.getProtocolId(), ResourceEventType.REQUEST_CREATED,
                    role.operatorId(), task.processInstanceKey, Map.of(
                            "requestedExamType", request.examType().name(), "requestedBodyPart", request.bodyPart().name(),
                            "requestedStart", selection.getStartTime().toString(), "requestedEnd", selection.getEndTime().toString(),
                            "requestedById", role.operatorId(), "requestedByName", name, "requestedByRole", role.validatorRole()));
        }
        return result;
    }

    private void requireProtocol(ExamType type, BodyPart part) {
        if (protocols.findActiveByExamTypeAndBodyPart(type, part, LocalDate.now()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nessun protocollo attivo per l'esame selezionato.");
        }
    }

    public record ExamOption(ExamType examType, BodyPart bodyPart) {}
    public record ExamOptions(List<ExamOption> combinations, LocalDate fromDate, LocalDate toDate) {}
}
