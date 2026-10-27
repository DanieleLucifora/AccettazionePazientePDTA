package it.uni.pdta.pdta_camunda.web;

import com.fasterxml.jackson.databind.JsonNode;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.*;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.BookingSlotRepository;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ResourceEventRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:diagnostic-report-http;DB_CLOSE_DELAY=-1",
        "camunda.client.worker.defaults.enabled=false", "pdta.kpi.enabled=false"
})
@ActiveProfiles("web-ui")
class DiagnosticReportHttpTests {
    @Autowired TestRestTemplate http;
    @Autowired DiagnosticExamService exams;
    @Autowired DiagnosticBookingStore store;
    @Autowired it.uni.pdta.pdta_camunda.scheduling.service.BookingService booking;
    @Autowired BookingSlotRepository slots;
    @Autowired ResourceEventRepository events;
    @MockitoBean CamundaRestClient rest;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService audit;

    @Test
    void reportPersistsCompletesSlotAndReturnsOnlyOwnResultWithoutDuplicateAudit() throws Exception {
        var fixture = booked("6101", true);
        var response = submit("6101", "  Referto dimostrativo completo.  ");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("SENT");
        assertThat(response.getBody().get("text").asText()).isEqualTo("Referto dimostrativo completo.");
        var read = http.getForEntity(path("6101"), JsonNode.class);
        assertThat(read.getBody().get("report").get("text").asText()).isEqualTo("Referto dimostrativo completo.");
        assertThat(slots.findById(fixture.slotId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.COMPLETED);
        verify(rest).completeTask(eq("6101"), argThat(vars ->
                "Referto dimostrativo completo.".equals(vars.get("oncologoExamResult"))
                        && !vars.containsKey("chirurgoExamResult") && !vars.containsKey("chirurgoRequiresExam")
                        && Boolean.FALSE.equals(vars.get("oncologoRequiresExam"))
                        && "6101".equals(vars.get("oncologoReportTaskKey"))
                        && Boolean.FALSE.equals(vars.get("examRejected"))
                        && fixture.slotId().toString().equals(vars.get("bookedSlotId"))));
        fixture.task().state = "COMPLETED";
        assertThat(submit("6101", "Referto dimostrativo completo.").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submit("6101", "Altro testo").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditCount(fixture.slotId())).isEqualTo(1);
        verify(rest, times(1)).completeTask(eq("6101"), anyMap());
    }

    @Test
    void enforcesMandatoryTextLengthEndTimeOwnershipAndActiveBooking() throws Exception {
        var future = booked("6102", false);
        assertThat(submit("6102", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(submit("6102", "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit("6102", "x".repeat(10001)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity(path("6102"), Map.of(), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var foreign = booked("6103", true);
        foreign.task().assignee = "LAB002";
        assertThat(submit("6103", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var canceled = booked("6104", true);
        var slot = slots.findById(canceled.slotId()).orElseThrow();
        slot.setStatus(SlotStatus.CANCELED);
        slots.saveAndFlush(slot);
        assertThat(submit("6104", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(auditCount(future.slotId()) + auditCount(foreign.slotId()) + auditCount(canceled.slotId())).isZero();
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void timeoutKeepsImmutableReportAndRetryResendsSameReportWithoutSecondAudit() throws Exception {
        var fixture = booked("6105", true);
        doThrow(new org.springframework.web.client.ResourceAccessException("Timeout"))
                .doNothing().when(rest).completeTask(eq("6105"), anyMap());
        assertThat(submit("6105", "Referto salvato").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        var read = http.getForEntity(path("6105"), JsonNode.class);
        assertThat(read.getBody().get("report").get("status").asText()).isEqualTo("PENDING");
        assertThat(read.getBody().get("report").get("text").asText()).isEqualTo("Referto salvato");
        assertThat(submit("6105", "Testo diverso").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(submit("6105", "Referto salvato").getBody().get("status").asText()).isEqualTo("SENT");
        assertThat(auditCount(fixture.slotId())).isEqualTo(1);
    }

    @Test
    void reconcilesLostAcknowledgementOnlyWithMatchingTaskCorrelationAndClinicalResult() throws Exception {
        booked("6106", true);
        doThrow(new org.springframework.web.client.ResourceAccessException("Lost response"))
                .when(rest).completeTask(eq("6106"), anyMap());
        assertThat(submit("6106", "Referto").getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        var fixtureTask = rest.searchTasks(Map.of("filter", Map.of("userTaskKey", "6106"))).getFirst();
        fixtureTask.state = "COMPLETED";
        var vars = rest.getTypedTaskVariables("6106");
        vars.put("oncologoExamResult", "Referto");
        vars.put("oncologoReportTaskKey", "wrong-task");
        assertThat(http.getForEntity(path("6106"), JsonNode.class).getBody().get("report").get("status").asText()).isEqualTo("PENDING");
        vars.put("oncologoReportTaskKey", "6106");
        assertThat(http.getForEntity(path("6106"), JsonNode.class).getBody().get("report").get("status").asText()).isEqualTo("SENT");
        verify(rest, times(1)).completeTask(eq("6106"), anyMap());
    }

    @Test
    void concurrentDifferentReportsCannotOverwriteTextOrDuplicateCompletionAudit() throws Exception {
        var fixture = booked("6107", true);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(); return submit("6107", "Referto A").getStatusCode(); });
            var second = executor.submit(() -> { barrier.await(); return submit("6107", "Referto B").getStatusCode(); });
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(auditCount(fixture.slotId())).isEqualTo(1);
        verify(rest, times(1)).completeTask(eq("6107"), anyMap());
    }

    @Test
    void endTimeThresholdIsExactAndCompletedExamsCannotBeCanceled() throws Exception {
        var fixture = booked("6108", false);
        var decision = store.find("6108").orElseThrow();
        var end = decision.getSlot().getEndTime();
        var request = DiagnosticRequestContext.from(fixture.task().elementId, rest.getTypedTaskVariables("6108"));
        assertThatThrownBy(() -> store.saveReport("6108", "6108", request, new DiagnosticReportCommand("Referto"), end.minusNanos(1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.find("6108").orElseThrow().getReportText()).isNull();
        store.saveReport("6108", "6108", request, new DiagnosticReportCommand("Referto"), end);
        assertThat(store.find("6108").orElseThrow().getReportText()).isEqualTo("Referto");
        assertThat(auditCount(fixture.slotId())).isEqualTo(1);
        assertThatThrownBy(() -> booking.cancelSlot(fixture.slotId(), "Annullamento"))
                .isInstanceOf(it.uni.pdta.pdta_camunda.scheduling.service.SlotConflictException.class);
    }

    @Test
    void auditFailureRollsBackReportAndSlotTogetherWithoutCompletingWorkflow() throws Exception {
        var fixture = booked("6109", true);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Audit unavailable"))
                .when(audit).logExamCompleted(argThat(slot -> slot != null && fixture.slotId().equals(slot.getId())),
                        eq("LAB001"), eq("6109"), eq("Referto"), anyMap());
        assertThat(submit("6109", "Referto").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(store.find("6109").orElseThrow().getReportText()).isNull();
        assertThat(slots.findById(fixture.slotId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(auditCount(fixture.slotId())).isZero();
        verify(rest, never()).completeTask(eq("6109"), anyMap());
    }

    @Test
    void missingOrRejectedBookingAndWrongElementCannotBeReported() throws Exception {
        var fixture = booked("6110", true);
        assertThat(http.getForEntity(path("6110"), JsonNode.class).getBody().get("report").isNull()).isTrue();
        fixture.task().elementId = "Activity_14jdrgj";
        assertThat(submit("6110", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity(path("6110"), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        fixture.task().candidateGroups = List.of("CHIRURGHI");
        assertThat(submit("6110", "Referto").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(rest, never()).completeTask(eq("6110"), anyMap());
        var noBookingTask = fixture.task();
        noBookingTask.userTaskKey = "6111";
        noBookingTask.elementId = "Activity_diagnostic_oncologo";
        noBookingTask.candidateGroups = List.of("LABORATORIO");
        when(rest.searchTasks(Map.of("filter", Map.of("userTaskKey", "6111")))).thenReturn(List.of(noBookingTask));
        var vars = rest.getTypedTaskVariables("6110");
        when(rest.getTypedTaskVariables("6111")).thenReturn(vars);
        assertThat(submit("6111", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        store.save("6111", "6110", "DEMO-REPORT-6110", DiagnosticRequestContext.from(noBookingTask.elementId, vars),
                new DiagnosticBookingCommand(DiagnosticBookingCommand.Action.REJECT, null, null, "Motivo"), null);
        assertThat(submit("6111", "Referto").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private org.springframework.http.ResponseEntity<JsonNode> submit(String key, String text) {
        return http.postForEntity(path(key), Map.of("text", text), JsonNode.class);
    }

    private long auditCount(Long slot) {
        return events.findAll().stream().filter(event -> slot.equals(event.getBookingSlotId())
                && event.getEventType() == ResourceEventType.EXAM_COMPLETED).count();
    }

    private Fixture booked(String key, boolean past) throws Exception {
        var task = new CamundaRestClient.UserTask();
        task.userTaskKey = key; task.processInstanceKey = key; task.state = "CREATED"; task.assignee = "LAB001";
        task.elementId = "Activity_diagnostic_oncologo"; task.candidateGroups = List.of("LABORATORIO");
        when(rest.searchTasks(Map.of("filter", Map.of("userTaskKey", key)))).thenReturn(List.of(task));
        var vars = new HashMap<String, Object>(Map.of(
                "patientID", "DEMO-REPORT-" + key, "oncologoRequiresExam", true, "chirurgoRequiresExam", true,
                "oncologoExamType", "TAC", "oncologoBodyPart", "TORACE", "diagnosticRequestRole", "ONCOLOGI",
                "oncologoRequesterId", "ON001", "oncologoRequesterName", "Luigi Rossi"));
        vars.put("examQueue", "CHIRURGHI,ONCOLOGI");
        when(rest.getTypedTaskVariables(key)).thenReturn(vars);
        var request = DiagnosticRequestContext.from(task.elementId, vars);
        var suggestion = exams.slots(ExamType.TAC, BodyPart.TORACE, LocalDate.now().plusDays(8)).getFirst();
        var decision = store.save(key, key, "DEMO-REPORT-" + key, request,
                new DiagnosticBookingCommand(DiagnosticBookingCommand.Action.CONFIRM,
                        suggestion.getResourceId(), suggestion.getStartTime(), ""), suggestion);
        if (past) {
            var slot = slots.findById(decision.getSlot().getId()).orElseThrow();
            slot.setStartTime(suggestion.getStartTime().minusDays(10));
            slot.setEndTime(suggestion.getEndTime().minusDays(10));
            slots.saveAndFlush(slot);
        }
        return new Fixture(task, decision.getSlot().getId());
    }

    private String path(String key) { return "/api/diagnostics/tasks/" + key + "/report"; }
    private record Fixture(CamundaRestClient.UserTask task, Long slotId) {}
}
