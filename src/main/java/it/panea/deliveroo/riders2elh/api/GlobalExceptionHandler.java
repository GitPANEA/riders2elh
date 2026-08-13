package it.panea.deliveroo.riders2elh.api;

import it.panea.deliveroo.riders2elh.common.ClientNonAutorizzatoException;
import it.panea.deliveroo.riders2elh.common.ConflittoConcorrenzaException;
import it.panea.deliveroo.riders2elh.common.RichiestaNonValidaException;
import it.panea.deliveroo.riders2elh.common.RisorsaNonTrovataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RisorsaNonTrovataException.class)
    public ResponseEntity<Map<String, Object>> gestisciNonTrovata(RisorsaNonTrovataException e) {
        return errore(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflittoConcorrenzaException.class)
    public ResponseEntity<Map<String, Object>> gestisciConflitto(ConflittoConcorrenzaException e) {
        return errore(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ClientNonAutorizzatoException.class)
    public ResponseEntity<Map<String, Object>> gestisciClientNonAutorizzato(ClientNonAutorizzatoException e) {
        return errore(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** Parametri di richiesta incoerenti tra loro (es. dataInizio successiva a dataFine). */
    @ExceptionHandler(RichiestaNonValidaException.class)
    public ResponseEntity<Map<String, Object>> gestisciRichiestaNonValida(RichiestaNonValidaException e) {
        return errore(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> gestisciValidazione(MethodArgumentNotValidException e) {
        return errore(HttpStatus.BAD_REQUEST, "Payload non valido: " + e.getMessage());
    }

    /**
     * Content-Type non accettato dall'endpoint (es. JSON verso POST /voci, che vuole
     * multipart/form-data). Senza questo handler l'eccezione resta al
     * DefaultHandlerExceptionResolver e Spring Security la restituisce al client come
     * 403 con WWW-Authenticate: insufficient_scope — un messaggio fuorviante, che manda
     * a cercare un problema di autorizzazione al posto di uno di Content-Type.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> gestisciMediaTypeNonSupportato(HttpMediaTypeNotSupportedException e) {
        String supportati = e.getSupportedMediaTypes().stream()
                .map(MediaType::toString)
                .collect(Collectors.joining(", "));
        return errore(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type non supportato: " + e.getContentType()
                        + (supportati.isEmpty() ? "" : ". Attesi: " + supportati));
    }

    /** Metodo HTTP non previsto sul path (es. GET su un endpoint solo POST). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> gestisciMetodoNonSupportato(HttpRequestMethodNotSupportedException e) {
        return errore(HttpStatus.METHOD_NOT_ALLOWED, "Metodo non supportato: " + e.getMessage());
    }

    /** Body assente o illeggibile (JSON malformato, multipart senza la parte attesa). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> gestisciBodyIllegibile(HttpMessageNotReadableException e) {
        return errore(HttpStatus.BAD_REQUEST, "Body della richiesta assente o non leggibile: " + e.getMessage());
    }

    /** Parametro di richiesta obbligatorio mancante (es. la parte 'file' su POST /voci). */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> gestisciParteMancante(MissingServletRequestPartException e) {
        return errore(HttpStatus.BAD_REQUEST, "Parte multipart mancante: " + e.getRequestPartName());
    }

    /**
     * Rete di sicurezza per ogni eccezione non prevista dagli handler sopra. Senza di essa
     * l'eccezione risale la filter chain di Spring Security, che la traduce in
     * {@code 403 WWW-Authenticate: insufficient_scope}: un errore interno si presenta al
     * client come problema di autorizzazione, mandando a cercare la causa nel token o negli
     * scope invece che nel codice. Con questo handler un bug applicativo risponde 500, e la
     * causa reale resta nel log lato server.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> gestisciNonPrevista(Exception e) {
        log.error("Errore non gestito durante l'elaborazione della richiesta", e);
        return errore(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno del server");
    }

    private ResponseEntity<Map<String, Object>> errore(HttpStatus status, String messaggio) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "errore", messaggio));
    }
}
