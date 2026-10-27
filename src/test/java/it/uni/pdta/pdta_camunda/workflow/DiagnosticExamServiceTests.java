package it.uni.pdta.pdta_camunda.workflow;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ExamProtocolRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.BookingService;
import it.uni.pdta.pdta_camunda.scheduling.service.ResourceEventService;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType.TAC;
import static it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart.TORACE;
import static it.uni.pdta.pdta_camunda.workflow.DiagnosticExamRequest.SchedulingPreference.*;
import static it.uni.pdta.pdta_camunda.workflow.SpecialistTaskService.SpecialistRole.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticExamServiceTests {
    private final CamundaRestClient rest = mock(CamundaRestClient.class);
    private final BookingService booking = mock(BookingService.class);
    private final ExamProtocolRepository protocols = mock(ExamProtocolRepository.class);
    private final ResourceEventService events = mock(ResourceEventService.class);
    private final DiagnosticExamService service = new DiagnosticExamService(
            new SpecialistTaskService(rest), rest, booking, protocols, events);
    private final LocalDate day = LocalDate.now().plusDays(1);
    private final ExamProtocol protocol = new ExamProtocol();
    private CamundaRestClient.UserTask task;

    @BeforeEach
    void setup() throws Exception {
        protocol.setId(9L);
        protocol.setExamType(TAC);
        protocol.setBodyPart(TORACE);
        protocol.setEffectiveFrom(LocalDate.now().minusDays(1));
        when(protocols.findActiveByExamTypeAndBodyPart(eq(TAC), eq(TORACE), any())).thenReturn(List.of(protocol));
        task = new CamundaRestClient.UserTask();
        task.userTaskKey = "42";
        task.processInstanceKey = "100";
        task.state = "CREATED";
        task.assignee = "CH001";
        task.elementId = "Activity_14jdrgj";
        task.candidateGroups = List.of("CHIRURGHI");
        when(rest.searchTasks(anyMap())).thenReturn(List.of(task));
        when(rest.getTypedTaskVariables("42")).thenReturn(Map.of("examQueue", "ONCOLOGI"));
    }

    @Test
    void automaticRequestPreservesQueueAndResetsOldManualSelection() throws Exception {
        var request = new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, null, null, " Nota ");
        assertThat(service.request("42", CHIRURGHI, request).status()).isEqualTo("COMPLETED");
        var captor = org.mockito.ArgumentCaptor.<Map<String, Object>>captor();
        verify(rest).completeTask(eq("42"), captor.capture());
        assertThat(captor.getValue()).containsEntry("requiresExam", true).containsEntry("isApproved", false)
                .containsEntry("examQueue", "ONCOLOGI,CHIRURGHI")
                .containsEntry("chirurgoRequesterId", "CH001").containsEntry("requesterName", "Marco Bianchi")
                .containsEntry("chirurgoSchedulingPreference", "AUTO_FIRST_AVAILABLE")
                .containsEntry("specialistSelectedSlotStart", "").containsEntry("chirurgoSelectedResourceId", "")
                .containsEntry("note", "Nota");
        verify(rest).claimTask("42", "CH001");
        verifyNoInteractions(booking, events);
    }

    @Test
    void manualRequestUsesCanonicalSlotInsteadOfClientSuppliedMetadata() throws Exception {
        var slot = slot();
        when(booking.getAvailableSlotsForDay(TAC, TORACE, day)).thenReturn(List.of(slot));
        service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, MANUAL_CALENDAR, 7L, day.atTime(9, 0), "Per laboratorio"));
        verify(rest).completeTask(eq("42"), argThat(vars -> vars.get("specialistSelectedProtocolId").equals("9")
                && vars.get("chirurgoSelectedSlotEnd").equals(day.atTime(9, 35).toString())
                && vars.get("preferredExamDate").equals(day.toString())
                && vars.get("specialistSelectedResourceCode").equals("TAC-TEST")));
        verify(events).logEvent(eq(7L), isNull(), eq(9L), eq(ResourceEventType.REQUEST_CREATED),
                eq("CH001"), eq("100"), anyMap());
        verify(booking, never()).bookSlot(anyLong(), anyLong(), any(), anyLong(), anyString(), anyString(), anyMap());
    }

    @Test
    void unavailableSlotCannotCompleteTaskOrWriteAudit() throws Exception {
        when(booking.getAvailableSlotsForDay(TAC, TORACE, day)).thenReturn(List.of());
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, MANUAL_CALENDAR, 7L, day.atTime(9, 0), "")));
        verify(rest, never()).completeTask(anyString(), anyMap());
        verifyNoInteractions(events);
    }

    @Test
    void missingOrForeignAssignmentClosedWrongRoleAndElementAreRejected() throws Exception {
        var request = new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, null, null, "");
        task.assignee = null;
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI, request));
        task.assignee = "OTHER";
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI, request));
        task.assignee = "CH001";
        task.state = "COMPLETED";
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI, request));
        task.state = "CREATED";
        task.elementId = "OTHER";
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI, request));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.request("42", ONCOLOGI, request));
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void rejectsMalformedSchedulingUnknownProtocolsAndOutOfRangeDates() throws Exception {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, 7L, day.atTime(9, 0), "")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, MANUAL_CALENDAR, null, null, "")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.slots(TAC, TORACE, LocalDate.now().minusDays(1)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.slots(TAC, TORACE, LocalDate.now().plusDays(10)));
        when(protocols.findActiveByExamTypeAndBodyPart(any(), any(), any())).thenReturn(List.of());
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, null, null, "")));
        verify(rest, never()).completeTask(anyString(), anyMap());
    }

    @Test
    void optionsExposeOnlyEffectiveActiveDistinctPairsAndAvailabilityNeverIncludesPastSlots() {
        var future = new ExamProtocol();
        future.setEffectiveFrom(LocalDate.now().plusDays(1));
        var inactive = new ExamProtocol();
        inactive.setActive(false);
        when(protocols.findAll()).thenReturn(List.of(protocol, protocol, future, inactive));
        assertThat(service.options().combinations()).containsExactly(new DiagnosticExamService.ExamOption(TAC, TORACE));
        when(booking.getAvailableSlotsForDay(TAC, TORACE, day)).thenReturn(List.of(slot()));
        assertThat(service.slots(TAC, TORACE, day)).hasSize(1);
        var elapsed = new SlotSuggestion(new DiagnosticResource(), protocol,
                LocalDate.now().atStartOfDay(), LocalDate.now().atTime(0, 35));
        when(booking.getAvailableSlotsForDay(TAC, TORACE, LocalDate.now())).thenReturn(List.of(elapsed));
        assertThat(service.slots(TAC, TORACE, LocalDate.now())).isEmpty();
    }

    @Test
    void brokerConflictDoesNotWriteRequestAuditAndConnectionFailuresAreNotSuccess() throws Exception {
        when(booking.getAvailableSlotsForDay(TAC, TORACE, day)).thenReturn(List.of(slot()));
        doThrow(org.springframework.web.client.HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", org.springframework.http.HttpHeaders.EMPTY, null, null))
                .when(rest).claimTask("42", "CH001");
        assertStatus(HttpStatus.CONFLICT, () -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, MANUAL_CALENDAR, 7L, day.atTime(9, 0), "")));
        verifyNoInteractions(events);
        verify(rest, never()).completeTask(anyString(), anyMap());
        var disconnected = new org.springframework.web.client.ResourceAccessException("Disconnected");
        doThrow(disconnected).when(rest).claimTask("42", "CH001");
        assertThatThrownBy(() -> service.request("42", CHIRURGHI,
                new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, null, null, "")))
                .isSameAs(disconnected);
        verifyNoInteractions(events);
    }

    @Test
    void oncologistPayloadKeepsOtherRoleOutAndDeduplicatesQueue() {
        var request = new DiagnosticExamRequest(TAC, TORACE, AUTO_FIRST_AVAILABLE, null, null, "");
        assertThat(request.variables(ONCOLOGI, "ON001", "Luigi Rossi", "Oncologo Medico",
                " CHIRURGHI,ONCOLOGI,CHIRURGHI ", null))
                .containsEntry("examQueue", "CHIRURGHI,ONCOLOGI")
                .containsEntry("oncologoRequesterId", "ON001")
                .doesNotContainKey("chirurgoRequesterId");
    }

    private SlotSuggestion slot() {
        var resource = new DiagnosticResource();
        resource.setId(7L);
        resource.setCode("TAC-TEST");
        return new SlotSuggestion(resource, protocol, day.atTime(9, 0), day.atTime(9, 35));
    }

    private void assertStatus(HttpStatus status, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
    }
}
