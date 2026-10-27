package it.uni.pdta.pdta_camunda.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService.SpecialistRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecialistTaskServiceTests {
    private final CamundaRestClient rest = mock(CamundaRestClient.class);
    private final SpecialistTaskService service = new SpecialistTaskService(rest);

    @Test
    void detailContainsOnlySelectedRoleReportAndNotes() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "CREATED", "CHIRURGHI")));
        when(rest.getTypedTaskVariables("42")).thenReturn(Map.of(
                "patientID", "001", "patientData", "Mario \"Rossi\"", "diagnosiCode", "6401",
                "clinicalFileUrl", "https://example.test/cartella",
                "chirurgoExamResult", "Referto chirurgico\nSeconda riga",
                "oncologoExamResult", "Referto oncologico", "examResult", "Generico",
                "chirurgoNote", "Note chirurgo", "oncologoNote", "Note oncologo"));
        var result = service.getTask("42", CHIRURGHI);
        assertThat(result.patientData()).isEqualTo("Mario \"Rossi\"");
        assertThat(result.examResult()).isEqualTo("Referto chirurgico\nSeconda riga");
        assertThat(result.note()).isEqualTo("Note chirurgo");
        assertThat(result.clinicalFileUrl()).isEqualTo("https://example.test/cartella");
        assertThat(result.assignee()).isEqualTo("CH001");
        verify(rest, never()).assignTask(anyString(), anyString());
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void oncologistUsesItsOwnReportAndLegacyOnlyWhenRequesterMatches() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "CREATED", "ONCOLOGI")));
        when(rest.getTypedTaskVariables("42")).thenReturn(
                Map.of("oncologoExamResult", "Oncologia", "chirurgoExamResult", "Chirurgia"),
                Map.of("examResult", "Legacy", "requesterRole", "ONCOLOGI"),
                Map.of("examResult", "Chirurgia", "requesterRole", "CHIRURGHI"));
        assertThat(service.getTask("42", ONCOLOGI).examResult()).isEqualTo("Oncologia");
        assertThat(service.getTask("42", ONCOLOGI).examResult()).isEqualTo("Legacy");
        assertThat(service.getTask("42", ONCOLOGI).examResult()).isEmpty();
    }

    @Test
    void missingWrongGroupAndMismatchedKeyNeverLoadVariables() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(),
                List.of(task("42", "CREATED", "ONCOLOGI")),
                List.of(task("99", "CREATED", "CHIRURGHI")));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.getTask("42", CHIRURGHI))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        verify(rest, never()).getTypedTaskVariables(anyString());
    }

    @Test
    void closedTaskIsConflictAndUpstreamErrorsArePropagated() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "COMPLETED", "CHIRURGHI")));
        assertThatThrownBy(() -> service.getTask("42", CHIRURGHI))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(rest, never()).getTypedTaskVariables(anyString());
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "CREATED", "CHIRURGHI")));
        when(rest.getTypedTaskVariables("42")).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.getTask("42", CHIRURGHI)).hasMessage("unavailable");
    }

    @Test
    void unsafeMalformedAndCredentialUrlsAreNotReturnedAsLinks() throws Exception {
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task("42", "CREATED", "CHIRURGHI")));
        for (String url : List.of("javascript:alert(1)", "//example.test/path", "not a url",
                "https://user:password@example.test/", "")) {
            when(rest.getTypedTaskVariables("42")).thenReturn(Map.of("clinicalFileUrl", url));
            assertThat(service.getTask("42", CHIRURGHI).clinicalFileUrl()).isNull();
        }
    }

    private CamundaRestClient.UserTask task(String key, String state, String group) {
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = key;
        task.processInstanceKey = "100";
        task.name = "Valutazione";
        task.assignee = "CH001";
        task.state = state;
        task.candidateGroups = List.of(group);
        return task;
    }
}
