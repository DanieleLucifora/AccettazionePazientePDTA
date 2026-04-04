package it.uni.pdta.pdta_camunda.scheduling.service;

public class SlotConflictException extends RuntimeException {

    public SlotConflictException(String message) {
        super(message);
    }

    public SlotConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
