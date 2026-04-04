package it.uni.pdta.pdta_camunda.scheduling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.BookingSlot;
import it.uni.pdta.pdta_camunda.scheduling.domain.entity.ResourceEvent;
import it.uni.pdta.pdta_camunda.scheduling.domain.enums.ResourceEventType;
import it.uni.pdta.pdta_camunda.scheduling.domain.repository.ResourceEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResourceEventService {

    private final ResourceEventRepository resourceEventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void logSlotAssigned(BookingSlot slot, String actor, String correlationId) {
        logSlotAssigned(slot, actor, correlationId, null);
    }

    public void logSlotAssigned(BookingSlot slot, String actor, String correlationId, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start", slot.getStartTime().toString());
        payload.put("end", slot.getEndTime().toString());
        payload.put("status", slot.getStatus().name());
        payload.putAll(sanitizePayload(extraPayload));
        logEvent(
                slot.getResource().getId(),
                slot.getId(),
                slot.getExamProtocol().getId(),
                ResourceEventType.SLOT_ASSIGNED,
                actor,
                correlationId,
                payload
        );
    }

    public void logSlotRescheduled(BookingSlot slot, String actor, String correlationId, String reason) {
        logSlotRescheduled(slot, actor, correlationId, reason, null);
    }

    public void logSlotRescheduled(BookingSlot slot,
                                   String actor,
                                   String correlationId,
                                   String reason,
                                   Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start", slot.getStartTime().toString());
        payload.put("end", slot.getEndTime().toString());
        payload.put("reason", reason);
        payload.putAll(sanitizePayload(extraPayload));
        logEvent(
                slot.getResource().getId(),
                slot.getId(),
                slot.getExamProtocol().getId(),
                ResourceEventType.SLOT_RESCHEDULED,
                actor,
                correlationId,
                payload
        );
    }

    public void logExamCompleted(BookingSlot slot, String actor, String correlationId, String summary) {
        logExamCompleted(slot, actor, correlationId, summary, null);
    }

    public void logExamCompleted(BookingSlot slot,
                                 String actor,
                                 String correlationId,
                                 String summary,
                                 Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.putAll(sanitizePayload(extraPayload));
        logEvent(
                slot.getResource().getId(),
                slot.getId(),
                slot.getExamProtocol().getId(),
                ResourceEventType.EXAM_COMPLETED,
                actor,
                correlationId,
                payload
        );
    }

    public void logExamCanceled(Long resourceId,
                                Long bookingSlotId,
                                Long examProtocolId,
                                String actor,
                                String correlationId,
                                String reason) {
        logExamCanceled(resourceId, bookingSlotId, examProtocolId, actor, correlationId, reason, null);
    }

    public void logExamCanceled(Long resourceId,
                                Long bookingSlotId,
                                Long examProtocolId,
                                String actor,
                                String correlationId,
                                String reason,
                                Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.putAll(sanitizePayload(extraPayload));
        logEvent(
                resourceId,
                bookingSlotId,
                examProtocolId,
                ResourceEventType.EXAM_CANCELED,
                actor,
                correlationId,
                payload
        );
    }

    public void logEvent(Long resourceId,
                         Long bookingSlotId,
                         Long examProtocolId,
                         ResourceEventType eventType,
                         String actor,
                         String correlationId,
                         Map<String, Object> payload) {
        if (resourceId == null || eventType == null) {
            return;
        }

        ResourceEvent event = new ResourceEvent();
        event.setResourceId(resourceId);
        event.setBookingSlotId(bookingSlotId);
        event.setExamProtocolId(examProtocolId);
        event.setEventType(eventType);
        event.setActor(actor);
        event.setCorrelationId(correlationId);
        event.setPayloadJson(toJson(payload));

        resourceEventRepository.save(event);
        log.info("[ResourceEventService] Event {} salvato (resourceId={}, slotId={})",
                eventType, resourceId, bookingSlotId);
    }

    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return sanitized;
        }

        payload.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                sanitized.put(key, value);
            }
        });

        return sanitized;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("[ResourceEventService] Impossibile serializzare payload evento: {}", e.getMessage());
            return null;
        }
    }
}