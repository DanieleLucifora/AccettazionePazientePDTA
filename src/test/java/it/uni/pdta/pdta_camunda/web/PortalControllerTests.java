package it.uni.pdta.pdta_camunda.web;

import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.AccessStatus;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.RegistrationProgress;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService;
import it.uni.pdta.pdta_camunda.workflow.DiagnosticExamService;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PortalController.class)
@ActiveProfiles("web-ui")
@ContextConfiguration(classes = {PortalController.class, PortalExceptionHandler.class})
class PortalControllerTests {
    @MockitoBean it.uni.pdta.pdta_camunda.workflow.DiagnosticReportService reports;
    @Autowired MockMvc mvc;
    @MockitoBean PatientRegistrationService registration;
    @MockitoBean SpecialistTaskService tasks;
    @MockitoBean DiagnosticExamService exams;
    @MockitoBean it.uni.pdta.pdta_camunda.workflow.DiagnosticTaskService diagnostics;

    @Test
    void examRoutesValidateDatesEnumsAndRequestShape() throws Exception {
        when(exams.options()).thenReturn(new DiagnosticExamService.ExamOptions(
                List.of(new DiagnosticExamService.ExamOption(ExamType.TAC, BodyPart.TORACE)),
                LocalDate.now(), LocalDate.now().plusDays(9)));
        mvc.perform(get("/api/exams/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.combinations[0].examType").value("TAC"));
        when(exams.slots(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/exams/slots").param("examType", "TAC").param("bodyPart", "TORACE")
                .param("day", LocalDate.now().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/exams/slots").param("examType", "OTHER").param("bodyPart", "TORACE")
                .param("day", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/exams/slots")).andExpect(status().isBadRequest());
        var role = SpecialistTaskService.SpecialistRole.CHIRURGHI;
        when(exams.request(eq("42"), eq(role), any()))
                .thenReturn(new SpecialistTaskService.TaskActionResult("42", "COMPLETED", "CH001"));
        mvc.perform(post("/api/tasks/42/exam-request").param("role", "CHIRURGHI").contentType("application/json")
                .content("""
                {"examType":"TAC","bodyPart":"TORACE","schedulingPreference":"AUTO_FIRST_AVAILABLE","note":"Nota"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.assignee").value("CH001"));
        for (String invalid : List.of("{}", """
                {"examType":"TAC","bodyPart":"TORACE","schedulingPreference":"MANUAL_CALENDAR"}
                """, """
                {"examType":"OTHER","bodyPart":"TORACE","schedulingPreference":"AUTO_FIRST_AVAILABLE"}
                """, """
                {"examType":"TAC","bodyPart":"TORACE","schedulingPreference":"MANUAL_CALENDAR","resourceId":7,"startTime":"bad"}
                """, "{\"examType\":\"TAC\",\"bodyPart\":\"TORACE\",\"schedulingPreference\":\"AUTO_FIRST_AVAILABLE\",\"note\":\""
                + "x".repeat(2001) + "\"}")) {
            mvc.perform(post("/api/tasks/42/exam-request").param("role", "CHIRURGHI")
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verify(exams, times(1)).request(eq("42"), eq(role), any());
    }

    @Test
    void claimsAndDecisionsUseServerSelectedOperatorAndValidateRejection() throws Exception {
        var role = SpecialistTaskService.SpecialistRole.CHIRURGHI;
        when(tasks.claim("42", role, "CH001"))
                .thenReturn(new SpecialistTaskService.TaskActionResult("42", "CLAIMED", "CH001"));
        mvc.perform(post("/api/tasks/42/claim").param("role", "CHIRURGHI"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee").value("CH001"));
        when(tasks.decide(eq("42"), eq(role), eq("CH001"), eq("Chirurgo Toracica"), any()))
                .thenReturn(new SpecialistTaskService.TaskActionResult("42", "COMPLETED", "CH001"));
        mvc.perform(post("/api/tasks/42/decision").param("role", "CHIRURGHI")
                .contentType("application/json").content("""
                {"outcome":"APPROVE","note":"Valutato","operatorId":"OTHER"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        for (String invalid : List.of(
                "{\"outcome\":\"REJECT\",\"note\":\"   \"}",
                "{\"outcome\":\"OTHER\"}", "{}",
                "{\"outcome\":\"APPROVE\",\"note\":\"" + "x".repeat(2001) + "\"}")) {
            mvc.perform(post("/api/tasks/42/decision").param("role", "CHIRURGHI")
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verify(tasks, times(1)).decide(eq("42"), eq(role), eq("CH001"), eq("Chirurgo Toracica"), any());
        mvc.perform(post("/api/tasks/abc/claim").param("role", "CHIRURGHI")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/42/claim").param("role", "OTHER")).andExpect(status().isBadRequest());
    }

    @Test
    void registrationReturnsCreatedAndResumableKey() throws Exception {
        when(registration.start(any(), eq("CM001"))).thenReturn(new RegistrationProgress("42", AccessStatus.WAITING));
        mvc.perform(post("/api/registrations").contentType("application/json")
                .content("""
                        {"patientID":"001","patientData":"Mario Rossi","diagnosiCode":"6401"}
                        """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.processInstanceKey").value("42"))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void rejectsInvalidPatientAndUnsafeUrlWithoutCallingWorkflow() throws Exception {
        for (String body : List.of(
                """
                {"patientID":" ","patientData":"Mario","diagnosiCode":"6401"}
                """,
                """
                {"patientID":"001","patientData":"Mario","diagnosiCode":"6401","clinicalFileUrl":"javascript:alert(1)"}
                """)) {
            mvc.perform(post("/api/registrations").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(registration);
    }

    @Test
    void malformedJsonAndMissingRoleReturnBadRequest() throws Exception {
        mvc.perform(post("/api/registrations").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks")).andExpect(status().isBadRequest());
    }

    @Test
    void completesAccessWithoutStartingNewInstance() throws Exception {
        when(registration.completeAccess("42", "CM001"))
                .thenReturn(new RegistrationProgress("42", AccessStatus.COMPLETED));
        mvc.perform(post("/api/registrations/42/complete-access"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(registration, never()).start(any(), anyString());
        mvc.perform(post("/api/registrations/not-a-key/complete-access")).andExpect(status().isBadRequest());
    }

    @Test
    void listsSpecialistTasksAndRejectsUnknownRole() throws Exception {
        when(tasks.findTasks(SpecialistTaskService.SpecialistRole.CHIRURGHI)).thenReturn(List.of());
        mvc.perform(get("/api/tasks").param("role", "CHIRURGHI"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/tasks").param("role", "ADMIN")).andExpect(status().isBadRequest());
    }

    @Test
    void upstreamFailureIsExplicitAndDoesNotLeakDetails() throws Exception {
        when(tasks.findTasks(any())).thenThrow(new IllegalStateException("private upstream detail"));
        mvc.perform(get("/api/tasks").param("role", "ONCOLOGI")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private upstream detail"))));
    }

    @Test
    void taskDetailValidatesKeyAndRoleAndUsesProblemDetails() throws Exception {
        var role = SpecialistTaskService.SpecialistRole.CHIRURGHI;
        when(tasks.getTask("42", role)).thenReturn(new SpecialistTaskService.TaskDetail(
                "42", "100", "Valutazione Chirurgo", "CREATED", null, role,
                "001", "Paziente fittizio", "6401", null, "Referto", "", "CH001"));
        mvc.perform(get("/api/tasks/42").param("role", "CHIRURGHI"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.examResult").value("Referto"));
        mvc.perform(get("/api/tasks/not-a-key").param("role", "CHIRURGHI")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/42").param("role", "ADMIN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks/42")).andExpect(status().isBadRequest());
        when(tasks.getTask("42", role)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Attivita' chiusa"));
        mvc.perform(get("/api/tasks/42").param("role", "CHIRURGHI"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Attivita' chiusa"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Attivita' non trovata")).when(tasks).getTask("42", role);
        mvc.perform(get("/api/tasks/42").param("role", "CHIRURGHI"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Attivita' non trovata"));
        doThrow(new IllegalStateException("upstream private")).when(tasks).getTask("42", role);
        mvc.perform(get("/api/tasks/42").param("role", "CHIRURGHI"))
                .andExpect(status().isBadGateway());
    }
}
