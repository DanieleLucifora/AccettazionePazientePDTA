package it.uni.pdta.pdta_camunda.web;

import com.fasterxml.jackson.databind.JsonNode;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.BookingSlotRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.DiagnosticBookingStore;
import it.uni.pdta.pdta_camunda.workflow.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named = "PDTA_REPORT_LIVE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:diagnostic-report-live;DB_CLOSE_DELAY=-1",
        "camunda.client.worker.defaults.enabled=false", "pdta.kpi.enabled=false"
})
@ActiveProfiles("web-ui")
class DiagnosticReportLiveTests {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired CamundaRestClient rest;
    @Autowired DiagnosticExamService exams;
    @Autowired DiagnosticBookingStore store;
    @Autowired BookingSlotRepository slots;

    @Test
    void browserReportsParallelRealCamundaRequestsUsingEndedBookingsInIsolatedDatabase() throws Exception {
        String processKey = null;
        try {
            String patient = "GUI-REPORT-" + System.currentTimeMillis();
            var creation = http.postForEntity("/api/registrations",
                    Map.of("patientID", patient, "firstName", "Paziente", "lastName", "fittizio refertazione isolata",
                            "birthDate", "1980-01-02", "diagnosiCode", "6401"),
                    JsonNode.class);
            assertThat(creation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            processKey = creation.getBody().get("processInstanceKey").asText();
            String key = processKey;
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                var response = http.postForEntity("/api/registrations/" + key + "/complete-access", null, JsonNode.class);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody().get("status").asText()).isEqualTo("COMPLETED");
            });
            List<String> initialKeys = new ArrayList<>();
            for (String role : List.of("CHIRURGHI", "ONCOLOGI")) {
                var specialist = awaitTask(key, role, null);
                initialKeys.add(specialist.userTaskKey);
                assertThat(http.postForEntity("/api/tasks/" + specialist.userTaskKey + "/claim?role=" + role, null, String.class)
                        .getStatusCode()).isEqualTo(HttpStatus.OK);
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                    var detail = http.getForEntity("/api/tasks/" + specialist.userTaskKey + "?role=" + role, JsonNode.class);
                    assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(detail.getBody().get("assignee").asText()).isEqualTo(role.equals("CHIRURGHI") ? "CH001" : "ON001");
                });
                assertThat(http.postForEntity("/api/tasks/" + specialist.userTaskKey + "/exam-request?role=" + role,
                        Map.of("examType", "TAC", "bodyPart", role.equals("CHIRURGHI") ? "TORACE" : "TESTA",
                                "schedulingPreference", "AUTO_FIRST_AVAILABLE", "note", "Verifica referto " + role), String.class)
                        .getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            List<String> diagnosticKeys = new ArrayList<>();
            for (String role : List.of("CHIRURGHI", "ONCOLOGI")) {
                String element = role.equals("CHIRURGHI") ? "Activity_1nqhbxs" : "Activity_diagnostic_oncologo";
                var task = awaitTask(key, "LABORATORIO", element);
                diagnosticKeys.add(task.userTaskKey);
                var suggestion = exams.slots(ExamType.TAC, role.equals("CHIRURGHI") ? BodyPart.TORACE : BodyPart.TESTA,
                        LocalDate.now().plusDays(8)).getFirst();
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                        assertThat(rest.getTypedTaskVariables(task.userTaskKey)).containsKey("diagnosticRequestRole"));
                var request = DiagnosticRequestContext.from(task.elementId, rest.getTypedTaskVariables(task.userTaskKey));
                store.save(task.userTaskKey, key, patient, request, new DiagnosticBookingCommand(
                        DiagnosticBookingCommand.Action.CONFIRM, suggestion.getResourceId(), suggestion.getStartTime(), ""), suggestion);
                assertThat(http.postForEntity("/api/diagnostics/tasks/" + task.userTaskKey + "/claim", null, String.class)
                        .getStatusCode()).isEqualTo(HttpStatus.OK);
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                        assertThat(rest.searchTasks(Map.of("filter", Map.of("userTaskKey", task.userTaskKey))).getFirst().assignee).isEqualTo("LAB001"));
                assertThat(http.postForEntity("/api/diagnostics/tasks/" + task.userTaskKey + "/report", Map.of("text", "Prematuro"),
                        String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                // Only this test database is changed: no production booking or time-control API is used.
                var slot = slots.findById(store.find(task.userTaskKey).orElseThrow().getSlot().getId()).orElseThrow();
                slot.setStartTime(slot.getStartTime().minusDays(10));
                slot.setEndTime(slot.getEndTime().minusDays(10));
                slots.saveAndFlush(slot);
            }
            var builder = new ProcessBuilder("npm", "run", "test:live", "--", "report-isolated.spec.ts")
                    .directory(new java.io.File("frontend")).redirectErrorStream(true);
            builder.environment().put("PDTA_LIVE_URL", "http://127.0.0.1:" + port);
            builder.environment().put("PDTA_REPORT_TASKS", String.join(",", diagnosticKeys));
            builder.environment().put("PDTA_REPORT_INITIAL_TASKS", String.join(",", initialKeys));
            builder.environment().put("PDTA_REPORT_PATIENT", patient);
            builder.environment().put("PDTA_REPORT_PROCESS", key);
            var output = java.nio.file.Files.createTempFile("pdta-report-browser-", ".log");
            try {
                var browser = builder.redirectOutput(output.toFile()).start();
                try {
                    if (!browser.waitFor(150, TimeUnit.SECONDS)) throw new AssertionError("Browser report validation timed out");
                    System.out.println(java.nio.file.Files.readString(output));
                    assertThat(browser.exitValue()).isZero();
                } finally {
                    if (browser.isAlive()) {
                        browser.descendants().forEach(ProcessHandle::destroyForcibly);
                        browser.destroyForcibly();
                        browser.waitFor(10, TimeUnit.SECONDS);
                    }
                }
            } finally {
                java.nio.file.Files.deleteIfExists(output);
            }
            for (String diagnosticKey : diagnosticKeys) {
                var decision = store.find(diagnosticKey).orElseThrow();
                assertThat(decision.getReportDeliveredAt()).isNotNull();
                assertThat(decision.getSlot().getStatus()).isEqualTo(SlotStatus.COMPLETED);
            }
            System.out.println("REPORT LIVE PASS: process=" + key + ", tasks=" + diagnosticKeys + ", production bookings=0");
        } finally {
            if (processKey != null) {
                new org.springframework.web.client.RestTemplate().postForEntity(
                        System.getenv().getOrDefault("PDTA_CAMUNDA_URL", "http://127.0.0.1:8080")
                                + "/v2/process-instances/" + processKey + "/cancellation", Map.of(), String.class);
                String key = processKey;
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                        assertThat(rest.getProcessInstanceState(key)).isEqualTo("TERMINATED"));
                System.out.println("Canceled synthetic report process " + key + "; isolated database discarded when test JVM exits.");
            }
        }
    }

    private CamundaRestClient.UserTask awaitTask(String process, String group, String element) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() ->
                rest.searchTasks(Map.of("filter", Map.of("processInstanceKey", process, "state", "CREATED", "candidateGroup", group)))
                        .stream().filter(task -> element == null || element.equals(task.elementId)).findFirst().orElse(null),
                java.util.Objects::nonNull);
    }
}
