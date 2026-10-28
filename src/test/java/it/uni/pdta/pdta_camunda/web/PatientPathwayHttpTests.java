package it.uni.pdta.pdta_camunda.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uni.pdta.pdta_camunda.workflow.CamundaRestClient;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticBookingDecision;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticBookingDecisionRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:pathways-http;DB_CLOSE_DELAY=-1",
        "camunda.client.worker.defaults.enabled=false", "pdta.kpi.enabled=false"
})
@ActiveProfiles("web-ui")
class PatientPathwayHttpTests {
    @Autowired TestRestTemplate http;
    @Autowired BookingService booking;
    @Autowired DiagnosticBookingDecisionRepository decisions;
    @Autowired DiagnosticBookingStore store;
    @MockitoBean CamundaRestClient rest;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void searchIsBoundedExactAndKeepsLegacyDemographicsWithoutInventingBirthDate() throws Exception {
        var process = process("42", "ACTIVE");
        when(rest.searchProcessInstances(anyMap())).thenReturn(json.valueToTree(Map.of("items", List.of(process),
                "page", Map.of("totalItems", 21, "hasMoreTotalItems", false))));
        when(rest.getProcessVariables("42")).thenReturn(Map.of("patientID", "0001", "patientData", "Anagrafica precedente"));
        var response = http.getForEntity("/api/pathways?patientId=0001&state=ACTIVE&offset=0", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("items").get(0).path("patientID").asText()).isEqualTo("0001");
        assertThat(response.getBody().path("items").get(0).path("birthDate").asText()).isEmpty();
        assertThat(response.getBody().path("hasNext").asBoolean()).isTrue();
        verify(rest).searchProcessInstances(Map.of("filter", Map.of("processDefinitionId", "presa_in_carico_pdta",
                        "state", "ACTIVE", "variables", List.of(Map.of("name", "patientID", "value", "\"0001\""))),
                "sort", List.of(Map.of("field", "startDate", "order", "DESC")), "page", Map.of("from", 0, "limit", 20)));
        http.getForEntity("/api/pathways?offset=20", JsonNode.class);
        verify(rest).searchProcessInstances(argThat(body -> Map.of("from", 20, "limit", 20).equals(body.get("page"))));
        for (String invalid : List.of("/api/pathways?offset=-1", "/api/pathways?state=INVALID", "/api/pathways/not-a-key")) {
            assertThat(http.getForEntity(invalid, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void detailCombinesHistoryBookingsAndPendingReportAndDoesNotTreatBranchRefusalAsWholeClosure() throws Exception {
        String key = "9142";
        when(rest.getProcessInstance(key)).thenReturn(process(key, "ACTIVE"));
        when(rest.getProcessVariables(key)).thenReturn(Map.of("patientID", "DEMO-HISTORY",
                "patientData", "Paziente fittizio", "chirurgoApproved", false, "chirurgoExamResult", "Vecchio referto"));
        when(rest.getProcessElements(key)).thenReturn(List.of(json.readTree("""
                {"elementInstanceKey":"11","elementId":"Event_0fff0ni","elementName":"Esclusione Paziente",
                 "state":"COMPLETED","type":"END_EVENT","startDate":"2026-01-01T10:00:00Z"}
                """)));
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = "9143"; task.name = "Valutazione Oncologo"; task.state = "CREATED";
        task.processInstanceKey = key; task.candidateGroups = List.of("ONCOLOGI"); task.assignee = "ON001";
        when(rest.searchTasks(Map.of("filter", Map.of("processInstanceKey", key)))).thenReturn(List.of(task));
        var suggestion = booking.getAvailableSlotsForDay(ExamType.TAC, BodyPart.TORACE, LocalDate.now().plusDays(3)).getFirst();
        var slot = booking.bookSlot(suggestion.getResourceId(), suggestion.getProtocolId(), suggestion.getStartTime(),
                Long.valueOf(key), "DEMO-HISTORY", "CHIRURGHI", Map.of());
        var item = new DiagnosticBookingDecision();
        item.setTaskKey("9144"); item.setProcessInstanceKey(Long.valueOf(key)); item.setRequesterRole("CHIRURGHI");
        item.setAction(DiagnosticBookingCommand.Action.CONFIRM); item.setReason(""); item.setSlot(slot);
        item.setDecidedAt(LocalDateTime.now()); item.setReportText("Referto salvato"); item.setReportedAt(LocalDateTime.now());
        decisions.saveAndFlush(item);
        var legacySuggestion = booking.getAvailableSlotsForDay(ExamType.TAC, BodyPart.TORACE, LocalDate.now().plusDays(4)).getFirst();
        booking.bookSlot(legacySuggestion.getResourceId(), legacySuggestion.getProtocolId(), legacySuggestion.getStartTime(),
                Long.valueOf(key), "DEMO-HISTORY", "ONCOLOGI", Map.of());
        var response = http.getForEntity("/api/pathways/" + key, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.path("pathway").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(body.path("outcome").asText()).isEqualTo("BRANCH_REJECTED");
        assertThat(body.path("tasks").get(0).path("assignee").asText()).isEqualTo("ON001");
        assertThat(body.path("surgeon").path("examResult").asText()).isEqualTo("Vecchio referto");
        assertThat(body.path("oncologist").path("approved").isNull()).isTrue();
        assertThat(body.path("exams").size()).isEqualTo(1);
        assertThat(body.path("exams").get(0).path("report").path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("historicalSlots").size()).isEqualTo(1);
        assertThat(store.historicalSlots(key)).hasSize(1);
        verify(rest, never()).completeTask(anyString(), anyMap());
        verify(rest, never()).claimTask(anyString(), anyString());
    }

    @Test
    void missingForeignAndFailedProcessesHaveDistinctErrorsNotFabricatedCompletion() throws Exception {
        when(rest.getProcessInstance("80")).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "missing", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        when(rest.getProcessInstance("81")).thenReturn(json.readTree("""
                {"processInstanceKey":"81","processDefinitionId":"another-process","state":"COMPLETED"}
                """));
        when(rest.getProcessInstance("82")).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThat(http.getForEntity("/api/pathways/80", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/pathways/81", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/pathways/82", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        when(rest.searchProcessInstances(anyMap())).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));
        assertThat(http.getForEntity("/api/pathways", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void optionsAndNewRegistrationValidationAreActuallyWiredOverHttp() {
        var options = http.getForEntity("/api/registrations/options", JsonNode.class);
        assertThat(options.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(options.getBody().path("diagnosisCodes").size()).isEqualTo(6);
        assertThat(options.getBody().path("today").asText()).isEqualTo(LocalDate.now().toString());
        for (var input : List.of(
                Map.of("patientID", "001", "patientData", "legacy", "diagnosiCode", "6401"),
                Map.of("patientID", "001", "firstName", "Paziente", "lastName", "Fittizio", "birthDate", "2020-02-31", "diagnosiCode", "6401"),
                Map.of("patientID", "001", "firstName", "Paziente", "lastName", "Fittizio", "birthDate", "1980-01-02", "diagnosiCode", "9999"))) {
            assertThat(http.postForEntity("/api/registrations", input, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    private JsonNode process(String key, String state) {
        return json.valueToTree(Map.of("processInstanceKey", key, "processDefinitionId", "presa_in_carico_pdta",
                "state", state, "processDefinitionVersion", 7, "hasIncident", false, "startDate", "2026-01-01T09:00:00Z"));
    }
}
