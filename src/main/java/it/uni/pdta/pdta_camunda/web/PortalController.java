package it.uni.pdta.pdta_camunda.web;

import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.RegistrationProgress;
import it.uni.pdta.pdta_camunda.registration.RegistrationRequest;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService;
import it.uni.pdta.pdta_camunda.workflow.SpecialistDecision;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService.SpecialistRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@Profile("web-ui")
public class PortalController {
    private static final String DEMO_CASE_MANAGER = "CM001";
    private final PatientRegistrationService registrationService;
    private final SpecialistTaskService taskService;

    public PortalController(PatientRegistrationService registrationService, SpecialistTaskService taskService) {
        this.registrationService = registrationService;
        this.taskService = taskService;
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationProgress register(@Valid @RequestBody RegistrationRequest request) {
        return registrationService.start(request, DEMO_CASE_MANAGER);
    }

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
}
