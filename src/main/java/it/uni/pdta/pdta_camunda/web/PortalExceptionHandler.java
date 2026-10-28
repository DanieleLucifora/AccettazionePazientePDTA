package it.uni.pdta.pdta_camunda.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.concurrent.CompletionException;

@RestControllerAdvice(assignableTypes = PortalController.class)
@Profile("web-ui")
public class PortalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PortalExceptionHandler.class);

    @ExceptionHandler({it.uni.pdta.pdta_camunda.scheduling.service.SlotConflictException.class,
            org.springframework.dao.DataIntegrityViolationException.class,
            org.springframework.dao.ConcurrencyFailureException.class})
    public ProblemDetail bookingConflict(Exception exception) {
        LOG.warn("Conflitto nella prenotazione diagnostica", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La fascia o la decisione sono cambiate. Aggiorna il dettaglio prima di un nuovo invio.");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ProblemDetail storageFailure(Exception exception) {
        LOG.error("Persistenza della prenotazione non confermata", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Impossibile confermare il salvataggio. Verifica lo stato prima di riprovare.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail unavailableTask(ResponseStatusException exception) {
        LOG.warn("Dettaglio attivita' non disponibile: {}", exception.getStatusCode());
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class})
    public ProblemDetail invalidRequest(Exception exception) {
        LOG.warn("Richiesta portale non valida: {}", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Controllare i campi obbligatori, le lunghezze e il formato dei dati.");
    }

    @ExceptionHandler({RestClientException.class, CompletionException.class,
            IOException.class, IllegalStateException.class})
    public ProblemDetail workflowFailure(Exception exception) {
        LOG.error("Operazione portale non riuscita", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Impossibile confermare l'operazione con Camunda. Se era un nuovo inserimento, "
                + "verificare su Operate prima di ripeterlo: il processo potrebbe essere stato creato.");
    }
}
