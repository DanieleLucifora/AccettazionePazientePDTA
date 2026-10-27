package it.uni.pdta.pdta_camunda.web;

import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.AccessStatus;
import it.uni.pdta.pdta_camunda.registration.PatientRegistrationService.RegistrationProgress;
import it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PortalController.class)
@ActiveProfiles("web-ui")
@ContextConfiguration(classes = {PortalController.class, PortalExceptionHandler.class})
class PortalControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean PatientRegistrationService registration;
    @MockitoBean SpecialistTaskService tasks;

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
}
