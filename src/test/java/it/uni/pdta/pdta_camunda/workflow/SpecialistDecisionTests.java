package it.uni.pdta.pdta_camunda.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static it.uni.pdta.pdta_camunda.workflow.SpecialistDecision.Outcome.*;
import static it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService.SpecialistRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistDecisionTests {
    private final CamundaRestClient rest = mock(CamundaRestClient.class);
    private final SpecialistTaskService service = new SpecialistTaskService(rest);

    @Test
    void claimVerifiesGroupAndDoesNotCompleteTask() throws Exception {
        open("42", null, "CHIRURGHI", "Activity_14jdrgj");
        assertThat(service.claim("42", CHIRURGHI, "CH001").status()).isEqualTo("CLAIMED");
        verify(rest).claimTask("42", "CH001");
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void anotherAssigneeIsNeverOverwritten() throws Exception {
        open("42", "CH002", "CHIRURGHI", "Activity_14jdrgj");
        assertConflict(() -> service.claim("42", CHIRURGHI, "CH001"));
        assertConflict(() -> service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, "")));
        verify(rest, never()).claimTask(anyString(), anyString());
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void unclaimedTaskRequiresExplicitClaimBeforeDecision() throws Exception {
        open("42", null, "CHIRURGHI", "Activity_14jdrgj");
        assertConflict(() -> service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, "")));
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void approveUsesExistingBpmnContractAndRechecksAssignmentAtBroker() throws Exception {
        open("42", "CH001", "CHIRURGHI", "Activity_14jdrgj");
        assertThat(service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, " Nota ")).status()).isEqualTo("COMPLETED");
        var ordered = inOrder(rest);
        ordered.verify(rest).searchTasks(anyMap());
        ordered.verify(rest).claimTask("42", "CH001");
        ordered.verify(rest).completeTask("42", Map.of(
                "isApproved", true, "requiresExam", false, "note", "Nota",
                "validatorId", "CH001", "validatorRole", "Chirurgo Toracica"));
    }

    @Test
    void rejectUsesOncologistIdentityAndRequiresReason() throws Exception {
        open("42", "ON001", "ONCOLOGI", "Activity_0vww6jw");
        assertThatThrownBy(() -> service.decide("42", ONCOLOGI, "ON001", "Oncologo Medico",
                new SpecialistDecision(REJECT, "  "))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(rest);
        service.decide("42", ONCOLOGI, "ON001", "Oncologo Medico", new SpecialistDecision(REJECT, "Non idoneo"));
        verify(rest).completeTask("42", Map.of("isApproved", false, "requiresExam", false,
                "note", "Non idoneo", "validatorId", "ON001", "validatorRole", "Oncologo Medico"));
    }

    @Test
    void wrongRoleAndUnsupportedTaskNeverExecuteCommands() throws Exception {
        open("42", null, "ONCOLOGI", "Activity_0vww6jw");
        assertThatThrownBy(() -> service.claim("42", CHIRURGHI, "CH001"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        open("42", null, "CHIRURGHI", "OTHER_TASK");
        assertConflict(() -> service.claim("42", CHIRURGHI, "CH001"));
        verify(rest, never()).claimTask(anyString(), anyString());
    }

    @Test
    void staleStateAndDuplicateCompletionAreConflictNotFalseSuccess() throws Exception {
        open("42", "CH001", "CHIRURGHI", "Activity_14jdrgj");
        doThrow(error(HttpStatus.NOT_FOUND)).when(rest).completeTask(eq("42"), anyMap());
        assertConflict(() -> service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, "")));
        verify(rest, times(1)).completeTask(eq("42"), anyMap());
    }

    @Test
    void brokerRejectingClaimPreventsCompletionEvenWhenIndexSaysMine() throws Exception {
        open("42", "CH001", "CHIRURGHI", "Activity_14jdrgj");
        doThrow(error(HttpStatus.CONFLICT)).when(rest).claimTask("42", "CH001");
        assertConflict(() -> service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, "")));
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void closedTaskCannotBeClaimedOrCompletedAgain() throws Exception {
        var closed = new CamundaRestClient.UserTask();
        closed.userTaskKey = "42";
        closed.state = "COMPLETED";
        closed.candidateGroups = List.of("CHIRURGHI");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(closed));
        assertConflict(() -> service.claim("42", CHIRURGHI, "CH001"));
        assertConflict(() -> service.decide("42", CHIRURGHI, "CH001", "Chirurgo Toracica",
                new SpecialistDecision(APPROVE, "")));
        verify(rest, never()).claimTask(anyString(), anyString());
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void forbiddenFailuresRemainExplicit() throws Exception {
        open("42", null, "CHIRURGHI", "Activity_14jdrgj");
        var forbidden = error(HttpStatus.FORBIDDEN);
        doThrow(forbidden).when(rest).claimTask("42", "CH001");
        assertThatThrownBy(() -> service.claim("42", CHIRURGHI, "CH001")).isSameAs(forbidden);
    }

    private void open(String key, String assignee, String group, String element) throws Exception {
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = key;
        task.state = "CREATED";
        task.assignee = assignee;
        task.elementId = element;
        task.candidateGroups = List.of(group);
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task));
    }

    private HttpClientErrorException error(HttpStatus status) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, null, null);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
