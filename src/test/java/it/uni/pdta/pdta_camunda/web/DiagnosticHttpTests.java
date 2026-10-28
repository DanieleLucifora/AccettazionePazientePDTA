package it.uni.pdta.pdta_camunda.web;

import it.uni.pdta.pdta_camunda.scheduling.domain.repository.BookingSlotRepository;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticBookingDecisionRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import it.uni.pdta.pdta_camunda.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static it.uni.pdta.pdta_camunda.workflow.DiagnosticBookingCommand.Action.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:diagnostic-http;DB_CLOSE_DELAY=-1",
        "camunda.client.worker.defaults.enabled=false", "pdta.kpi.enabled=false"
})
@ActiveProfiles("web-ui")
class DiagnosticHttpTests {
    @Autowired TestRestTemplate http;
    @Autowired DiagnosticExamService exams;
    @Autowired DiagnosticBookingStore store;
    @Autowired BookingSlotRepository slots;
    @Autowired DiagnosticBookingDecisionRepository decisions;
    @Autowired DiagnosticTaskService diagnostics;
    @MockitoBean CamundaRestClient rest;

    @Test
    void confirmPersistsSingleBookingAcrossRefreshAndStoreReadsItBack() throws Exception {
        var task = task("5101", "Activity_1nqhbxs", "CHIRURGHI");
        when(rest.searchTasks(Map.of("filter", Map.of("state", "CREATED", "candidateGroup", "LABORATORIO"))))
                .thenReturn(List.of(task));
        var list = http.getForEntity("/api/diagnostics/tasks", com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().size()).isEqualTo(1);
        assertThat(list.getBody().get(0).get("request").get("requesterRole").asText()).isEqualTo("CHIRURGHI");
        assertThat(http.postForEntity(path("5101") + "/claim", null, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var detail = detail("5101");
        var proposal = detail.proposal();
        assertThat(proposal).isNotNull();
        var command = new DiagnosticBookingCommand(CONFIRM, proposal.getResourceId(), proposal.getStartTime(), "");
        var result = http.postForEntity(path("5101") + "/booking", command, com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("status").asText()).isEqualTo("BOOKED");
        assertThat(detail("5101").decision().slotId()).isEqualTo(result.getBody().get("decision").get("slotId").asText());
        var persisted = store.find("5101").orElseThrow();
        assertThat(persisted.getSlot().getResource().getCode()).isEqualTo(proposal.getResourceCode());
        assertThat(persisted.getSlot().getExamProtocol().getExamType().name()).isEqualTo("TAC");
        assertThat(http.postForEntity(path("5101") + "/booking", command, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(slots.findByProcessInstanceKey(5101L)).hasSize(1);
        verify(rest, never()).completeTask(eq("5101"), anyMap());
    }

    @Test
    void modifyRequiresReasonAndUsesCanonicalAlternativeWithoutCompletingCamunda() throws Exception {
        task("5102", "Activity_diagnostic_oncologo", "ONCOLOGI");
        var slot = exams.slots(it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType.TAC,
                it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart.TORACE, LocalDate.now().plusDays(5)).getFirst();
        assertThat(http.postForEntity(path("5102") + "/booking",
                new DiagnosticBookingCommand(MODIFY, slot.getResourceId(), slot.getStartTime(), ""), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var response = http.postForEntity(path("5102") + "/booking",
                new DiagnosticBookingCommand(MODIFY, slot.getResourceId(), slot.getStartTime(), "Adeguamento agenda"),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("decision").get("action").asText()).isEqualTo("MODIFY");
        assertThat(store.find("5102").orElseThrow().getRequesterRole()).isEqualTo("ONCOLOGI");
        verify(rest, never()).completeTask(eq("5102"), anyMap());
    }

    @Test
    void rejectionSavesReasonReturnsOnlyOwnRequestAndRetryDoesNotDuplicateDecision() throws Exception {
        task("5103", "Activity_diagnostic_oncologo", "ONCOLOGI");
        var command = new DiagnosticBookingCommand(REJECT, null, null, "Esame non eseguibile");
        doThrow(new org.springframework.web.client.ResourceAccessException("Timeout"))
                .doNothing().when(rest).completeTask(eq("5103"), anyMap());
        assertThat(http.postForEntity(path("5103") + "/booking", command, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(detail("5103").decision().action()).isEqualTo(REJECT);
        assertThat(slots.findByProcessInstanceKey(5103L)).isEmpty();
        var retried = http.postForEntity(path("5103") + "/booking", command, DiagnosticTaskService.BookingResult.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().status()).isEqualTo("COMPLETED");
        verify(rest, times(2)).completeTask(eq("5103"), argThat(vars ->
                "ONCOLOGI".equals(vars.get("requesterRole")) && Boolean.FALSE.equals(vars.get("oncologoRequiresExam"))
                        && !vars.containsKey("chirurgoRequiresExam") && Boolean.TRUE.equals(vars.get("examRejected"))
                        && "CHIRURGHI".equals(vars.get("examQueue"))));
        assertThat(decisions.findById("5103")).isPresent();
    }

    @Test
    void foreignAssigneeWrongGroupClosedTaskAndMalformedInputsDoNotBook() throws Exception {
        var task = task("5104", "Activity_1nqhbxs", "CHIRURGHI");
        task.assignee = "LAB002";
        assertThat(http.postForEntity(path("5104") + "/claim", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        var command = new DiagnosticBookingCommand(REJECT, null, null, "Motivo");
        assertThat(http.postForEntity(path("5104") + "/booking", command, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        task.assignee = "LAB001";
        task.state = "COMPLETED";
        assertThat(http.getForEntity(path("5104"), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        task.state = "CREATED";
        task.candidateGroups = List.of("CHIRURGHI");
        assertThat(http.getForEntity(path("5104"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity(path("invalid") + "/claim", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity(path("5104") + "/booking", Map.of("action", "OTHER"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(store.find("5104")).isEmpty();
        verify(rest, never()).completeTask(eq("5104"), anyMap());
    }

    @Test
    void simultaneousBookingsForSameTaskOrResourceNeverCreateTwoActiveSlots() throws Exception {
        task("5105", "Activity_1nqhbxs", "CHIRURGHI");
        task("5106", "Activity_diagnostic_oncologo", "ONCOLOGI");
        var slot = exams.slots(it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType.TAC,
                it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart.TORACE, LocalDate.now().plusDays(6)).getFirst();
        var command = new DiagnosticBookingCommand(MODIFY, slot.getResourceId(), slot.getStartTime(), "Agenda");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var first = executor.submit(() -> { barrier.await(); return http.postForEntity(path("5105") + "/booking", command, String.class).getStatusCode(); });
            var second = executor.submit(() -> { barrier.await(); return http.postForEntity(path("5106") + "/booking", command, String.class).getStatusCode(); });
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(slots.findOverlapping(slot.getResourceId(), slot.getStartTime(), slot.getEndTime())).hasSize(1);
        assertThat(slots.findByProcessInstanceKey(5105L).size() + slots.findByProcessInstanceKey(5106L).size()).isEqualTo(1);

        task("5107", "Activity_1nqhbxs", "CHIRURGHI");
        var alternatives = exams.slots(it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType.TAC,
                it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart.TORACE, LocalDate.now().plusDays(7));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var futures = alternatives.subList(0, 2).stream().map(alternative -> executor.submit(() -> {
                barrier.await();
                return http.postForEntity(path("5107") + "/booking",
                        new DiagnosticBookingCommand(MODIFY, alternative.getResourceId(), alternative.getStartTime(), "Agenda"),
                        String.class).getStatusCode();
            })).toList();
            assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(slots.findByProcessInstanceKey(5107L)).hasSize(1);
    }

    private DiagnosticTaskService.DiagnosticTask detail(String key) throws Exception {
        var result = http.getForEntity(path(key), String.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return diagnostics.detail(key);
    }

    private CamundaRestClient.UserTask task(String key, String element, String role) throws Exception {
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = key; task.processInstanceKey = key; task.state = "CREATED";
        task.elementId = element; task.assignee = "LAB001"; task.candidateGroups = List.of("LABORATORIO");
        when(rest.searchTasks(Map.of("filter", Map.of("userTaskKey", key)))).thenReturn(List.of(task));
        var vars = new java.util.HashMap<String, Object>();
        vars.put("patientID", "DEMO-LAB-" + key); vars.put("patientData", "Paziente fittizio");
        vars.put("diagnosticRequestRole", role); vars.put("examQueue", "CHIRURGHI,ONCOLOGI");
        for (String prefix : List.of("chirurgo", "oncologo")) {
            vars.put(prefix + "RequiresExam", true); vars.put(prefix + "ExamType", "TAC");
            vars.put(prefix + "BodyPart", "TORACE"); vars.put(prefix + "SchedulingPreference", "AUTO_FIRST_AVAILABLE");
            vars.put(prefix + "RequesterId", prefix.equals("chirurgo") ? "CH001" : "ON001");
            vars.put(prefix + "RequesterName", prefix.equals("chirurgo") ? "Marco Bianchi" : "Luigi Rossi");
        }
        when(rest.getTypedTaskVariables(key)).thenReturn(vars);
        return task;
    }

    private String path(String key) { return "/api/diagnostics/tasks/" + key; }
}
