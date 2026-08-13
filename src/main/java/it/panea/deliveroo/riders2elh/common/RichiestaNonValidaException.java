package it.panea.deliveroo.riders2elh.common;

/**
 * Parametri di richiesta incoerenti tra loro: sintatticamente validi (quindi non
 * intercettabili con le annotazioni di validazione su un singolo campo) ma non accettabili
 * nella combinazione ricevuta — es. {@code dataInizio} successiva a {@code dataFine}.
 * Mappata su 400 da {@code GlobalExceptionHandler}.
 */
public class RichiestaNonValidaException extends RuntimeException {
    public RichiestaNonValidaException(String messaggio) {
        super(messaggio);
    }
}
