package it.panea.deliveroo.riders2elh.common;

/**
 * IN_CORSO: elaborazione avviata su thread separato, non ancora conclusa (§ 9, esecuzione
 * asincrona). ERRORE_TECNICO: il task asincrono si è interrotto per un'eccezione fuori dal
 * ciclo per-record (es. avviaElaborazione/chiudiBatch falliti) — distinto da KO, che indica
 * un batch arrivato in fondo con tutti i record scartati per un problema di dato.
 */
public enum EsitoBatch { IN_CORSO, OK, KO, PARZIALE, ERRORE_TECNICO }
