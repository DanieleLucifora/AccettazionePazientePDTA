package it.uni.pdta.pdta_camunda.scheduling.domain.enums;

public enum ResourceEventType {
    REQUEST_CREATED,
    SLOT_ASSIGNED,
    SLOT_RESCHEDULED,
    EXAM_STARTED,
    EXAM_COMPLETED,
    EXAM_CANCELED,
    NO_SHOW,
    RESOURCE_DOWN,
    RESOURCE_UP,
    MAINTENANCE_PLANNED,
    MAINTENANCE_COMPLETED,
    PROTOCOL_UPDATED
}
