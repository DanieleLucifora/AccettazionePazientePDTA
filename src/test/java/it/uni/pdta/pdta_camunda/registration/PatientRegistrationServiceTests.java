package it.uni.pdta.pdta_camunda.registration;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatientRegistrationServiceTests {
    private final CamundaRestClient rest = mock(CamundaRestClient.class);
    private final CamundaClient camunda = mock(CamundaClient.class, RETURNS_DEEP_STUBS);
    private final PatientRegistrationService service = new PatientRegistrationService(camunda, rest);

    @Test
    void startsWithExistingBpmnVariablesAndStringKey() {
        var request = new RegistrationRequest(" rssmra80a01h501u ", " Mario ", " \"Rossi\" ", java.time.LocalDate.of(1980, 1, 2), "6401", null);
        var event = mock(ProcessInstanceEvent.class);
        when(event.getProcessInstanceKey()).thenReturn(2251799813685249L);
        when(camunda.newCreateInstanceCommand().bpmnProcessId("presa_in_carico_pdta").latestVersion()
                .variables(request.variables("CM001")).send().join()).thenReturn(event);

        var result = service.start(request, "CM001");

        assertThat(result.processInstanceKey()).isEqualTo("2251799813685249");
        assertThat(result.status()).isEqualTo(PatientRegistrationService.AccessStatus.WAITING);
        assertThat(request.variables("CM001")).containsEntry("patientID", "RSSMRA80A01H501U")
                .containsEntry("patientData", "Mario \"Rossi\"").doesNotContainKey("clinicalFileUrl");
    }

    @Test
    void completesOnlyMatchingAccessTaskAndUsesOperatorIdentity() throws Exception {
        var other = task("99", "Activity_0ia88wj", "CREATED");
        var specialist = task("42", "Activity_14jdrgj", "CREATED");
        var access = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(other, specialist, access));

        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.COMPLETED);
        verify(rest).assignTask("42-Activity_0ia88wj", "CM001");
        verify(rest).completeTask("42-Activity_0ia88wj", Map.of());
        verifyNoInteractions(camunda);
    }

    @Test
    void delayedVisibilityDoesNotCreateAnotherProcess() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of());
        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.WAITING);
        verifyNoInteractions(camunda);
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void alreadyCompletedAccessIsNotCompletedAgain() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "Activity_0ia88wj", "COMPLETED")));
        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.COMPLETED);
        verify(rest, never()).assignTask(anyString(), anyString());
    }

    @Test
    void upstreamFailureIsNotReportedAsWaitingOrSuccess() throws Exception {
        when(rest.searchTasks(anyMap())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.completeAccess("42", "CM001"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assignmentNotFoundWithStaleIndexRemainsWaitingWithoutCompletingAgain() throws Exception {
        var stale = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale));
        doThrow(notFound()).when(rest).assignTask(stale.userTaskKey, "CM001");

        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.WAITING);
        verify(rest, times(2)).searchTasks(anyMap());
        verify(rest, never()).completeTask(anyString(), anyMap());
        verifyNoInteractions(camunda);
    }

    @Test
    void completionNotFoundIsReconciledWithConfirmedCompletedState() throws Exception {
        var stale = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale),
                List.of(task("42", "Activity_0ia88wj", "COMPLETED")));
        doThrow(notFound()).when(rest).completeTask(stale.userTaskKey, Map.of());

        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.COMPLETED);
        verify(rest, times(1)).completeTask(stale.userTaskKey, Map.of());
    }

    @Test
    void disappearedTaskDoesNotBecomeAFalseSuccess() throws Exception {
        var stale = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale), List.of());
        doThrow(notFound()).when(rest).assignTask(stale.userTaskKey, "CM001");
        assertThat(service.completeAccess("42", "CM001").status())
                .isEqualTo(PatientRegistrationService.AccessStatus.WAITING);
    }

    @Test
    void canceledTaskKeepsCommandErrorInsteadOfSuccess() throws Exception {
        var stale = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale),
                List.of(task("42", "Activity_0ia88wj", "CANCELED")));
        var rejection = notFound();
        doThrow(rejection).when(rest).assignTask(stale.userTaskKey, "CM001");
        assertThatThrownBy(() -> service.completeAccess("42", "CM001")).isSameAs(rejection);
    }

    @Test
    void forbiddenAssignmentAndReconciliationOutageAreNotHidden() throws Exception {
        var stale = task("42", "Activity_0ia88wj", "CREATED");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale));
        var forbidden = HttpClientErrorException.create(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, null, null);
        doThrow(forbidden).when(rest).assignTask(stale.userTaskKey, "CM001");
        assertThatThrownBy(() -> service.completeAccess("42", "CM001")).isSameAs(forbidden);
        verify(rest, times(1)).searchTasks(anyMap());

        doThrow(notFound()).when(rest).assignTask(stale.userTaskKey, "CM001");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(stale))
                .thenThrow(new IllegalStateException("search unavailable"));
        assertThatThrownBy(() -> service.completeAccess("42", "CM001"))
                .isInstanceOf(IllegalStateException.class).hasMessage("search unavailable");
    }

    private HttpClientErrorException notFound() {
        return HttpClientErrorException.create(
                org.springframework.http.HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    }

    private CamundaRestClient.UserTask task(String key, String element, String state) {
        var task = new CamundaRestClient.UserTask();
        task.processInstanceKey = key;
        task.userTaskKey = key + "-" + element;
        task.elementId = element;
        task.state = state;
        return task;
    }
}
