package it.uni.pdta.pdta_camunda.scheduling.service;

import it.uni.pdta.pdta_camunda.scheduling.config.SchedulingProperties;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.BookingSlot;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.DiagnosticResource;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ExamProtocol;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.BodyPart;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ExamType;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceStatus;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.BookingSlotRepository;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.DiagnosticResourceRepository;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ExamProtocolRepository;
import it.uni.pdta.pdta_camunda.scheduling.service.dto.SlotSuggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingSlotRepository      bookingSlotRepository;
    private final DiagnosticResourceRepository resourceRepository;
    private final ExamProtocolRepository     protocolRepository;
    private final ResourceEventService       resourceEventService;
    private final SchedulingProperties       props;

    // -------------------------------------------------------------------------
    // Ricerca slot — nessuna scrittura su DB, senza transazione necessaria
    // -------------------------------------------------------------------------

    /**
     * Trova il PRIMO slot disponibile a partire da {@code fromDate}, scorrendo fino a 30 giorni
     * in avanti su tutte le risorse compatibili con il tipo esame.
     */
    public Optional<SlotSuggestion> findFirstAvailableSlot(ExamType examType,
                                                           BodyPart bodyPart,
                                                           LocalDate fromDate) {
        ExamProtocol protocol = resolveProtocol(examType, bodyPart);
        List<DiagnosticResource> resources = getCompatibleResources(examType);
        if (resources.isEmpty()) {
            return Optional.empty();
        }

        int neededMinutes = protocol.getTotalSlotMinutes() + props.getSlotBufferMinutes();
        LocalTime workStart = LocalTime.parse(props.getWorkDayStart());
        LocalTime workEnd   = LocalTime.parse(props.getWorkDayEnd());

        for (int dayOffset = 0; dayOffset < 30; dayOffset++) {
            LocalDate candidate = fromDate.plusDays(dayOffset);
            for (DiagnosticResource resource : resources) {
                Optional<LocalDateTime> gap = findFirstGap(
                        resource.getId(), candidate, workStart, workEnd, neededMinutes);
                if (gap.isPresent()) {
                    LocalDateTime slotStart = gap.get();
                    LocalDateTime slotEnd   = slotStart.plusMinutes(protocol.getTotalSlotMinutes());
                    return Optional.of(new SlotSuggestion(resource, protocol, slotStart, slotEnd));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Restituisce TUTTI gli slot liberi di un determinato giorno, su tutte le risorse
     * compatibili, già partizionati in finestre della durata del protocollo + buffer.
     * Usato per il calendario manuale: lo specialista vede la griglia e sceglie.
     */
    public List<SlotSuggestion> getAvailableSlotsForDay(ExamType examType,
                                                        BodyPart bodyPart,
                                                        LocalDate day) {
        ExamProtocol protocol = resolveProtocol(examType, bodyPart);
        List<DiagnosticResource> resources = getCompatibleResources(examType);

        int totalMinutes = protocol.getTotalSlotMinutes();
        int stepMinutes  = totalMinutes + props.getSlotBufferMinutes();
        LocalTime workStart = LocalTime.parse(props.getWorkDayStart());
        LocalTime workEnd   = LocalTime.parse(props.getWorkDayEnd());

        List<SlotSuggestion> result = new ArrayList<>();

        for (DiagnosticResource resource : resources) {
            List<BookingSlot> booked = bookingSlotRepository.findOverlapping(
                    resource.getId(),
                    day.atTime(workStart),
                    day.atTime(workEnd));

            LocalDateTime cursor = day.atTime(workStart);
            LocalDateTime dayEnd = day.atTime(workEnd);

            while (!cursor.plusMinutes(totalMinutes).isAfter(dayEnd)) {
                final LocalDateTime slotStart = cursor;
                final LocalDateTime slotEnd   = cursor.plusMinutes(totalMinutes);

                boolean overlaps = booked.stream().anyMatch(s ->
                        s.getStartTime().isBefore(slotEnd) && s.getEndTime().isAfter(slotStart));

                if (!overlaps) {
                    result.add(new SlotSuggestion(resource, protocol, slotStart, slotEnd));
                }
                cursor = cursor.plusMinutes(stepMinutes);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Prenotazione con lock pessimistico e retry
    // -------------------------------------------------------------------------

    /**
     * Prenota uno slot con lock pessimistico sulla risorsa.
     * In caso di conflitto di lock o ottimistico, riprova fino a {@code bookingRetryAttempts} volte.
     *
     * @throws SlotConflictException se lo slot risulta occupato o se il lock non è acquisibile
     */
    @Transactional
    public BookingSlot bookSlot(Long resourceId,
                                Long examProtocolId,
                                LocalDateTime startTime,
                                Long processInstanceKey,
                                String patientId,
                                String createdByRole,
                                Map<String, Object> eventMetadata) {
        int maxAttempts = props.getBookingRetryAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doBook(resourceId, examProtocolId, startTime,
                        processInstanceKey, patientId, createdByRole, eventMetadata);
            } catch (PessimisticLockingFailureException | ObjectOptimisticLockingFailureException e) {
                log.warn("[BookingService] Conflitto lock tentativo {}/{} su risorsa {}. {}",
                        attempt, maxAttempts, resourceId, e.getMessage());
                if (attempt == maxAttempts) {
                    throw new SlotConflictException(
                            "Impossibile acquisire lock su risorsa " + resourceId +
                            " dopo " + maxAttempts + " tentativi.", e);
                }
            }
        }
        // non raggiungibile
        throw new SlotConflictException("Prenotazione fallita per risorsa " + resourceId);
    }

    /**
     * Annulla uno slot esistente. Non elimina il record: lo porta a CANCELED per preservare lo storico.
     */
    @Transactional
    public void cancelSlot(Long slotId, String reason) {
        BookingSlot slot = bookingSlotRepository.findById(slotId)
                .orElseThrow(() -> new IllegalArgumentException("Slot non trovato: " + slotId));
        slot.setStatus(SlotStatus.CANCELED);
        slot.setCancelReason(reason);
        BookingSlot saved = bookingSlotRepository.save(slot);
        resourceEventService.logExamCanceled(
            saved.getResource().getId(),
            saved.getId(),
            saved.getExamProtocol().getId(),
            "booking-service",
            saved.getProcessInstanceKey() != null ? String.valueOf(saved.getProcessInstanceKey()) : null,
            reason
        );
        log.info("[BookingService] Slot {} annullato. Motivo: {}", slotId, reason);
    }

    // -------------------------------------------------------------------------
    // Helpers privati
    // -------------------------------------------------------------------------

    /** Esegue la prenotazione effettiva all'interno della transazione con lock. */
    private BookingSlot doBook(Long resourceId, Long examProtocolId, LocalDateTime startTime,
                               Long processInstanceKey,
                               String patientId,
                               String createdByRole,
                               Map<String, Object> eventMetadata) {

        DiagnosticResource resource = resourceRepository.findByIdWithLock(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Risorsa non trovata: " + resourceId));

        ExamProtocol protocol = protocolRepository.findById(examProtocolId)
                .orElseThrow(() -> new IllegalArgumentException("Protocollo non trovato: " + examProtocolId));

        LocalDateTime endTime = startTime.plusMinutes(protocol.getTotalSlotMinutes());

        List<BookingSlot> overlapping = bookingSlotRepository.findOverlapping(
                resourceId, startTime, endTime);
        if (!overlapping.isEmpty()) {
            throw new SlotConflictException(
                    String.format("Slot sovrapposto su %s dalle %s alle %s",
                            resource.getCode(), startTime, endTime));
        }

        BookingSlot slot = new BookingSlot();
        slot.setResource(resource);
        slot.setExamProtocol(protocol);
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setStatus(SlotStatus.BOOKED);
        slot.setProcessInstanceKey(processInstanceKey);
        slot.setPatientId(patientId);
        slot.setCreatedByRole(createdByRole);

        BookingSlot saved = bookingSlotRepository.save(slot);
        resourceEventService.logSlotAssigned(
            saved,
            createdByRole,
            processInstanceKey != null ? String.valueOf(processInstanceKey) : null,
            eventMetadata
        );
        log.info("[BookingService] Slot prenotato: id={} risorsa={} dalle {} alle {} per {}",
                saved.getId(), resource.getCode(), startTime, endTime, createdByRole);
        return saved;
    }

    /** Recupera il protocollo attivo corrente per la coppia examType+bodyPart. */
    private ExamProtocol resolveProtocol(ExamType examType, BodyPart bodyPart) {
        return protocolRepository
                .findActiveByExamTypeAndBodyPart(examType, bodyPart, LocalDate.now())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Nessun protocollo attivo per " + examType + " + " + bodyPart));
    }

    /** Filtra le risorse disponibili (AVAILABLE) che supportano il tipo esame richiesto. */
    private List<DiagnosticResource> getCompatibleResources(ExamType examType) {
        return resourceRepository.findByStatus(ResourceStatus.AVAILABLE).stream()
                .filter(r -> r.getSupportedExamTypes().contains(examType))
                .toList();
    }

    /**
     * Cerca il primo slot libero di {@code neededMinutes} in un giorno specifico per una risorsa.
     * Algoritmo: scorre i gap tra gli slot prenotati esistenti.
     */
    private Optional<LocalDateTime> findFirstGap(Long resourceId, LocalDate day,
                                                  LocalTime workStart, LocalTime workEnd,
                                                  int neededMinutes) {
        LocalDateTime dayBegin = day.atTime(workStart);
        LocalDateTime dayEnd   = day.atTime(workEnd);

        List<BookingSlot> booked = bookingSlotRepository.findOverlapping(
                resourceId, dayBegin, dayEnd);
        booked.sort(Comparator.comparing(BookingSlot::getStartTime));

        LocalDateTime cursor = dayBegin;

        for (BookingSlot busy : booked) {
            // c'è un gap tra cursor e l'inizio dello slot corrente?
            if (!cursor.plusMinutes(neededMinutes).isAfter(busy.getStartTime())) {
                return Optional.of(cursor);
            }
            // avanza cursor alla fine dello slot occupato (+ buffer già incluso in neededMinutes)
            if (busy.getEndTime().isAfter(cursor)) {
                cursor = busy.getEndTime().plusMinutes(props.getSlotBufferMinutes());
            }
        }

        // controlla lo spazio residuo dopo l'ultimo slot
        if (!cursor.plusMinutes(neededMinutes).isAfter(dayEnd)) {
            return Optional.of(cursor);
        }
        return Optional.empty();
    }
}
