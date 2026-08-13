package it.panea.deliveroo.riders2elh.common;

public class ClientNonAutorizzatoException extends RuntimeException {
    public ClientNonAutorizzatoException(String messaggio) {
        super(messaggio);
    }
}
