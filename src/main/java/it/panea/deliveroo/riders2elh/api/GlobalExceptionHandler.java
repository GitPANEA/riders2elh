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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import org.springframework.context.MessageSourceResolvable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
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

    /**
     * Parametro di richiesta non convertibile nel tipo dichiarato dal controller: una data che
     * non rispetta {@code yyyy-MM-dd}, un id non numerico, un enum inesistente su un parametro
     * tipizzato. È un errore del chiamante, quindi 400 e non 500.
     * <p>
     * Il messaggio nomina il parametro e, per i tipi con un formato atteso, lo indica: la
     * {@code ConversionFailedException} sottostante da sola direbbe solo che il parse è
     * fallito, lasciando indovinare quale dei parametri della query string sia il colpevole.
     * Senza questo handler l'eccezione cadeva nella rete {@code Exception} e rispondeva 500 —
     * osservato in dev il 13 agosto 2026 passando un ISO datetime completo a {@code dataInizio}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> gestisciTipoParametroErrato(MethodArgumentTypeMismatchException e) {
        Class<?> atteso = e.getRequiredType();
        String formato = "";
        if (atteso != null) {
            if (LocalDate.class.isAssignableFrom(atteso)) {
                formato = " Formato atteso: yyyy-MM-dd (solo la data, senza orario).";
            } else if (Number.class.isAssignableFrom(atteso) || atteso.isPrimitive()) {
                formato = " È atteso un valore numerico.";
            } else if (atteso.isEnum()) {
                formato = " Valori ammessi: " + Arrays.stream(atteso.getEnumConstants())
                        .map(String::valueOf).collect(Collectors.joining(", ")) + ".";
            }
        }
        return errore(HttpStatus.BAD_REQUEST, "Valore non valido per il parametro '" + e.getName()
                + "': '" + e.getValue() + "'." + formato);
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
     * Violazione di @Valid quando l'argomento validato non è un singolo oggetto ma una
     * collezione grezza (es. {@code @RequestBody @Valid List<RiderAnagraficaDto>} nelle tre
     * POST di caricamento): da Spring Framework 6.1 questo caso non passa più per
     * {@link MethodArgumentNotValidException}, ma per questo nuovo tipo, introdotto insieme al
     * meccanismo di "method validation". Senza questo handler cadeva nella rete
     * {@code Exception}→500, presentando come bug del server quello che Spring stesso aveva già
     * classificato come 400 (lo status è nel messaggio stesso dell'eccezione — vedi
     * {@code e.getStatusCode()}) — osservato in dev il 31 agosto 2026 su un caricamento reale
     * del cliente, con un record che violava un vincolo sui DTO della lista.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> gestisciValidazioneMetodo(HandlerMethodValidationException e) {
        // e.getMessage() da solo vale "400 BAD_REQUEST \"Validation failure\"": non nomina né il
        // campo né il vincolo violato. Il dettaglio reale sta negli AllValidationResults, uno per
        // parametro annotato coinvolto (qui in pratica sempre il singolo @RequestBody List<...>);
        // getContainerIndex() è l'indice nella lista quando il parametro è una collezione, quindi
        // qui coincide con la posizione del record malformato nel payload originale.
        String dettaglio = e.getAllValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(errore -> (r.getContainerIndex() != null ? "elemento " + r.getContainerIndex() + ": " : "")
                                + errore.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return errore(HttpStatus.BAD_REQUEST, "Payload non valido: "
                + (dettaglio.isBlank() ? e.getMessage() : dettaglio));
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

    /**
     * File multipart più grande del limite configurato ({@code spring.servlet.multipart.max-file-size},
     * oggi 20MB — rilevante per {@code POST /voci}, l'unico endpoint con upload). Senza questo
     * handler l'eccezione cadeva nella rete {@code Exception}→500: un limite di dimensione
     * violato dal chiamante è un errore della richiesta, non un bug del server.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> gestisciUploadTroppoGrande(MaxUploadSizeExceededException e) {
        return errore(HttpStatus.PAYLOAD_TOO_LARGE, "File troppo grande. Limite massimo consentito superato.");
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
