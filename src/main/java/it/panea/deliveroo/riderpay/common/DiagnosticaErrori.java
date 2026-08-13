package it.panea.deliveroo.riderpay.common;

import java.sql.SQLException;

/**
 * Estrazione del messaggio diagnostico completo da un'eccezione.
 *
 * <p>Il getMessage() delle eccezioni Spring si ferma al prefisso generico: una
 * BadSqlGrammarException riporta solo "PreparedStatementCallback; bad SQL grammar []"
 * e il codice ORA- vero resta nella SQLException sottostante. Senza scendere lungo la
 * catena delle cause, cio che finisce in T_BATCH_CARICAMENTO_ERRORE non e sufficiente
 * a diagnosticare il problema.
 *
 * <p>Nota: la traduzione Spring puo essere fuorviante — ORA-01950 ("no privileges on
 * tablespace", quindi un problema di quota) viene mappato su BadSqlGrammarException,
 * che di grammatica SQL non ha nulla. Il codice ORA- e l'unico dato affidabile.
 */
public final class DiagnosticaErrori {

    /** Limite della colonna MESSAGGIO_ERRORE (VARCHAR2(4000)), con margine. */
    private static final int LUNGHEZZA_MASSIMA = 3900;

    private DiagnosticaErrori() {
    }

    /**
     * Concatena il messaggio dell'eccezione con quello di tutte le cause, esplicitando
     * il codice ORA- di ogni SQLException incontrata. Il risultato e troncato per
     * stare in MESSAGGIO_ERRORE.
     */
    public static String messaggioCompleto(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        for (Throwable t = e.getCause(); t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof SQLException sql) {
                sb.append(" | ORA-").append(sql.getErrorCode()).append(": ").append(sql.getMessage());
            } else {
                sb.append(" | ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            }
        }
        return sb.length() > LUNGHEZZA_MASSIMA ? sb.substring(0, LUNGHEZZA_MASSIMA) : sb.toString();
    }
}
