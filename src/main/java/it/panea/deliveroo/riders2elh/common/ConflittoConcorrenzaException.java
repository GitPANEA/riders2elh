package it.panea.deliveroo.riders2elh.common;

public class ConflittoConcorrenzaException extends RuntimeException {
    public ConflittoConcorrenzaException(String messaggio, Throwable causa) {
        super(messaggio, causa);
    }
}
