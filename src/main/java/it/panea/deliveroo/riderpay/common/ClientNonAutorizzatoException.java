package it.panea.deliveroo.riderpay.common;

public class ClientNonAutorizzatoException extends RuntimeException {
    public ClientNonAutorizzatoException(String messaggio) {
        super(messaggio);
    }
}
