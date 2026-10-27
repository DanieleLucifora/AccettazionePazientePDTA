package it.uni.pdta.pdta_camunda.web;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import it.uni.pdta.pdta_camunda.registration.RegistrationRequest;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;
import it.uni.pdta.pdta_camunda.workflow.SpecialistDecision;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:portal-http;DB_CLOSE_DELAY=-1",
        "camunda.client.worker.defaults.enabled=false",
        "pdta.kpi.enabled=false"
})
@ActiveProfiles("web-ui")
class PortalHttpTests {
    @Autowired TestRestTemplate http;
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) CamundaClient camunda;
    @MockitoBean CamundaRestClient restClient;

    @Test
    void realHttpServerWiresRegistrationCompletionAndSpecialistQueries() throws Exception {
        var input = new RegistrationRequest("DEMO-HTTP", "Paziente dimostrativo", "6401", "");
        var instance = mock(ProcessInstanceEvent.class);
        when(instance.getProcessInstanceKey()).thenReturn(42L);
        when(camunda.newCreateInstanceCommand().bpmnProcessId("presa_in_carico_pdta").latestVersion()
                .variables(input.variables("CM001")).send().join()).thenReturn(instance);

        var created = http.postForEntity("/api/registrations", input, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("processInstanceKey", "42").containsEntry("status", "WAITING");

        var access = new CamundaRestClient.UserTask();
        access.userTaskKey = "access-42";
        access.processInstanceKey = "42";
        access.elementId = "Activity_0ia88wj";
        access.state = "CREATED";
        when(restClient.searchTasks(Map.of("filter", Map.of("processInstanceKey", "42"))))
                .thenReturn(List.of(access));
        var completed = http.postForEntity("/api/registrations/42/complete-access", null, Map.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).containsEntry("status", "COMPLETED");
        verify(restClient).assignTask("access-42", "CM001");

        var specialist = new CamundaRestClient.UserTask();
        specialist.userTaskKey = "43";
        specialist.processInstanceKey = "42";
        specialist.name = "Valutazione Chirurgo";
        specialist.state = "CREATED";
        specialist.elementId = "Activity_14jdrgj";
        specialist.candidateGroups = List.of("CHIRURGHI");
        when(restClient.searchTasks(Map.of("filter", Map.of("state", "CREATED", "candidateGroup", "CHIRURGHI"))))
                .thenReturn(List.of(specialist));
        when(restClient.getTypedTaskVariables("43")).thenReturn(input.variables("CM001"));
        when(restClient.searchTasks(Map.of("filter", Map.of("userTaskKey", "43"))))
                .thenReturn(List.of(specialist));
        var tasks = http.getForEntity("/api/tasks?role=CHIRURGHI", String.class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tasks.getBody()).contains("DEMO-HTTP", "Valutazione Chirurgo", "Paziente dimostrativo");
        var detail = http.getForEntity("/api/tasks/43?role=CHIRURGHI", String.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).contains("\"state\":\"CREATED\"", "DEMO-HTTP", "\"role\":\"CHIRURGHI\"");
        assertThat(http.getForEntity("/api/tasks/43?role=ONCOLOGI", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/tasks?role=ADMIN", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var claim = http.postForEntity("/api/tasks/43/claim?role=CHIRURGHI", null, Map.class);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claim.getBody()).containsEntry("status", "CLAIMED").containsEntry("assignee", "CH001");
        specialist.assignee = "CH001";
        var decision = http.postForEntity("/api/tasks/43/decision?role=CHIRURGHI",
                new SpecialistDecision(SpecialistDecision.Outcome.APPROVE, "Valutazione positiva"), Map.class);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decision.getBody()).containsEntry("status", "COMPLETED");
        verify(restClient).completeTask("43", Map.of(
                "isApproved", true, "requiresExam", false, "note", "Valutazione positiva",
                "validatorId", "CH001", "validatorRole", "Chirurgo Toracica"));
        specialist.state = "COMPLETED";
        assertThat(http.postForEntity("/api/tasks/43/decision?role=CHIRURGHI",
                new SpecialistDecision(SpecialistDecision.Outcome.APPROVE, ""), String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verify(restClient, times(1)).completeTask(eq("43"), anyMap());
    }
}
