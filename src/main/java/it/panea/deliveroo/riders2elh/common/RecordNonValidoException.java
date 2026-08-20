package it.panea.deliveroo.riders2elh.common;

import java.util.List;

/** Lanciata da ValidatoreFormato quando un record del batch ha violazioni di formato/coerenza. */
public class RecordNonValidoException extends RuntimeException {

    private final List<String> violazioni;

    public RecordNonValidoException(List<String> violazioni) {
        super(String.join("; ", violazioni));
        this.violazioni = violazioni;
    }

    public List<String> violazioni() {
        return violazioni;
    }
}
