package it.uni.pdta.pdta_camunda.registration;

import io.camunda.client.CamundaClient;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;

public class PatientRegistrationService {
    private static final Logger LOG = LoggerFactory.getLogger(PatientRegistrationService.class);
    private static final String ACCESS_ELEMENT = "Activity_0ia88wj";
    private final CamundaClient camundaClient;
    private final CamundaRestClient restClient;

    public PatientRegistrationService(CamundaClient camundaClient, CamundaRestClient restClient) {
        this.camundaClient = camundaClient;
        this.restClient = restClient;
    }

    public RegistrationProgress start(RegistrationRequest request, String caseManagerId) {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            if (!violations.isEmpty()) {
                throw new jakarta.validation.ConstraintViolationException(violations);
            }
        }
        long key = camundaClient.newCreateInstanceCommand()
                .bpmnProcessId("presa_in_carico_pdta").latestVersion()
                .variables(request.variables(caseManagerId)).send().join().getProcessInstanceKey();
        LOG.info("Registrazione avviata: processInstanceKey={}, caseManagerId={}", key, caseManagerId);
        return new RegistrationProgress(String.valueOf(key), AccessStatus.WAITING);
    }

    public RegistrationProgress completeAccess(String processInstanceKey, String caseManagerId) throws Exception {
        List<CamundaRestClient.UserTask> tasks = restClient.searchTasks(
                Map.of("filter", Map.of("processInstanceKey", processInstanceKey)));
        for (CamundaRestClient.UserTask task : tasks) {
            if (!processInstanceKey.equals(task.processInstanceKey) || !ACCESS_ELEMENT.equals(task.elementId)) {
                continue;
            }
            if ("COMPLETED".equals(task.state)) {
                return new RegistrationProgress(processInstanceKey, AccessStatus.COMPLETED);
            }
            if ("CREATED".equals(task.state)) {
                try {
                    restClient.assignTask(task.userTaskKey, caseManagerId);
                    // The patient variables were persisted when the instance was created.
                    restClient.completeTask(task.userTaskKey, Map.of());
                    return new RegistrationProgress(processInstanceKey, AccessStatus.COMPLETED);
                } catch (HttpClientErrorException.NotFound exception) {
                    return reconcileAccess(processInstanceKey, task.userTaskKey, exception);
                }
            }
        }
        LOG.info("Task accesso non ancora disponibile: processInstanceKey={}", processInstanceKey);
        return new RegistrationProgress(processInstanceKey, AccessStatus.WAITING);
    }

    private RegistrationProgress reconcileAccess(String processInstanceKey, String taskKey,
                                                  HttpClientErrorException.NotFound rejection) throws Exception {
        LOG.warn("Task accesso {} non trovato dal comando; verifica indice per processo {}",
                taskKey, processInstanceKey);
        List<CamundaRestClient.UserTask> refreshed = restClient.searchTasks(
                Map.of("filter", Map.of("processInstanceKey", processInstanceKey)));
        for (CamundaRestClient.UserTask task : refreshed) {
            if (!processInstanceKey.equals(task.processInstanceKey)
                    || !ACCESS_ELEMENT.equals(task.elementId) || !taskKey.equals(task.userTaskKey)) {
                continue;
            }
            if ("COMPLETED".equals(task.state)) {
                return new RegistrationProgress(processInstanceKey, AccessStatus.COMPLETED);
            }
            if (!"CREATED".equals(task.state)) {
                // A canceled task is not evidence that access completed successfully.
                throw rejection;
            }
        }
        LOG.warn("Esito accesso {} non ancora confermabile dall'indice; nuova verifica necessaria", taskKey);
        return new RegistrationProgress(processInstanceKey, AccessStatus.WAITING);
    }

    public enum AccessStatus { WAITING, COMPLETED }

    public record RegistrationProgress(String processInstanceKey, AccessStatus status) {}
}
