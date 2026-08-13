package it.panea.deliveroo.riderpay.common;

public class ConflittoConcorrenzaException extends RuntimeException {
    public ConflittoConcorrenzaException(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
