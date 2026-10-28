package it.uni.pdta.pdta_camunda.web;

import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.RegistrationProgress;
import it.uni.pdta.pdta_camunda.registration.RegistrationRequest;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService;
import it.uni.pdta.pdta_camunda.workflow.SpecialistDecision;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticExamRequest;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticExamService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticTaskService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticReportCommand;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticReportService;
import it.uni.pdta.pdta_camunda.workflow.PatientPathwayService;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService.SpecialistRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@Profile("web-ui")
public class PortalController {
    private static final String DEMO_CASE_MANAGER = "CM001";
    private final PatientRegistrationService registrationService;
    private final SpecialistTaskService taskService;
    private final DiagnosticExamService exams;
    private final DiagnosticTaskService diagnostics;
    private final DiagnosticReportService reports;
    private final PatientPathwayService pathways;

    public PortalController(PatientRegistrationService registrationService, SpecialistTaskService taskService,
            DiagnosticExamService exams, DiagnosticTaskService diagnostics, DiagnosticReportService reports, PatientPathwayService pathways) {
        this.registrationService = registrationService;
        this.taskService = taskService;
        this.exams = exams;
        this.diagnostics = diagnostics;
        this.reports = reports;
        this.pathways = pathways;
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationProgress register(@Valid @RequestBody RegistrationRequest request) {
        return registrationService.start(request, DEMO_CASE_MANAGER);
    }

    @GetMapping("/registrations/options")
    public RegistrationOptions registrationOptions() {
        return new RegistrationOptions(it.uni.pdta.pdta_camunda.registration.DiagnosisCatalog.codes(), LocalDate.now());
    }

    public record RegistrationOptions(List<String> diagnosisCodes, LocalDate today) {}

    @PostMapping("/registrations/{key}/complete-access")
    public RegistrationProgress completeAccess(@PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key)
            throws Exception {
        return registrationService.completeAccess(key, DEMO_CASE_MANAGER);
    }

    @GetMapping("/tasks")
    public List<SpecialistTaskService.TaskSummary> tasks(@RequestParam SpecialistRole role) throws Exception {
        return taskService.findTasks(role);
    }

    @GetMapping("/tasks/{key}")
    public SpecialistTaskService.TaskDetail task(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @RequestParam SpecialistRole role) throws Exception {
        return taskService.getTask(key, role);
    }

    @PostMapping("/tasks/{key}/claim")
    public SpecialistTaskService.TaskActionResult claim(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @RequestParam SpecialistRole role) throws Exception {
        return taskService.claim(key, role, role.operatorId());
    }

    @PostMapping("/tasks/{key}/decision")
    public SpecialistTaskService.TaskActionResult decide(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @RequestParam SpecialistRole role, @Valid @RequestBody SpecialistDecision decision) throws Exception {
        return taskService.decide(key, role, role.operatorId(), role.validatorRole(), decision);
    }

    @GetMapping("/exams/options")
    public DiagnosticExamService.ExamOptions examOptions() {
        return exams.options();
    }

    @GetMapping("/exams/slots")
    public List<SlotSuggestion> examSlots(@RequestParam ExamType examType, @RequestParam BodyPart bodyPart,
            @RequestParam LocalDate day) {
        return exams.slots(examType, bodyPart, day);
    }

    @PostMapping("/tasks/{key}/exam-request")
    public SpecialistTaskService.TaskActionResult requestExam(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @RequestParam SpecialistRole role, @Valid @RequestBody DiagnosticExamRequest request) throws Exception {
        return exams.request(key, role, request);
    }

    @GetMapping("/diagnostics/tasks")
    public List<DiagnosticTaskService.DiagnosticTask> diagnosticTasks() throws Exception {
        return diagnostics.list();
    }

    @GetMapping("/diagnostics/tasks/{key}")
    public DiagnosticTaskService.DiagnosticTask diagnosticTask(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key) throws Exception {
        return diagnostics.detail(key);
    }

    @PostMapping("/diagnostics/tasks/{key}/claim")
    public SpecialistTaskService.TaskActionResult claimDiagnosticTask(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key) throws Exception {
        return diagnostics.claim(key);
    }

    @PostMapping("/diagnostics/tasks/{key}/booking")
    public DiagnosticTaskService.BookingResult diagnosticBooking(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @Valid @RequestBody DiagnosticBookingCommand command) throws Exception {
        return diagnostics.decide(key, command);
    }

    @GetMapping("/diagnostics/tasks/{key}/report")
    public ReportStatus diagnosticReport(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key) throws Exception {
        return new ReportStatus(reports.status(key));
    }

    @PostMapping("/diagnostics/tasks/{key}/report")
    public DiagnosticReportService.ReportView submitDiagnosticReport(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key,
            @Valid @RequestBody DiagnosticReportCommand command) throws Exception {
        return reports.submit(key, command);
    }

    public record ReportStatus(DiagnosticReportService.ReportView report) {}

    @GetMapping("/pathways")
    public PatientPathwayService.PathwayPage pathways(
            @RequestParam(defaultValue = "") @jakarta.validation.constraints.Size(max = 64) String patientId,
            @RequestParam(defaultValue = "") @Pattern(regexp = "^$|ACTIVE|COMPLETED|TERMINATED") String state,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int offset) throws Exception {
        return pathways.list(patientId.trim(), state, offset);
    }

    @GetMapping("/pathways/{key}")
    public PatientPathwayService.PathwayDetail pathway(
            @PathVariable @Pattern(regexp = "[1-9][0-9]{0,18}") String key) throws Exception {
        return pathways.detail(key);
    }
}
