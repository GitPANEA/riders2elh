--------------------------------------------------------------------------------
-- Migrazione 2026-08-20 — esecuzione asincrona: nuovi esiti IN_CORSO e ERRORE_TECNICO.
--
-- Con l'elaborazione del batch su thread separato, ESITO smette di restare
-- NULL durante l'elaborazione: viene scritto esplicitamente a 'IN_CORSO' non
-- appena il task asincrono parte (avviaElaborazione), cosi si distingue "mai
-- iniziato" da "in corso" senza aggiungere una colonna. ESITO torna NULL solo
-- per i batch creati prima di questa migrazione e mai piu toccati (nessun
-- backfill, coerente col modello append-only).
--
-- ERRORE_TECNICO segnala un task asincrono interrotto da un'eccezione fuori
-- dal ciclo per-record (es. connessione persa durante avviaElaborazione o
-- chiudiBatch): distinto da KO, che indica un batch arrivato in fondo con
-- tutti i record scartati per un problema di dato.
--
-- CK_BATCH_ESITO va ricreato: DROP + ADD, perche' Oracle non supporta
-- ALTER CONSTRAINT sulla condizione di un CHECK.
--
-- ESITO era VARCHAR2(10): sufficiente per 'IN_CORSO' (8 caratteri) ma non per
-- 'ERRORE_TECNICO' (14 caratteri) -> ORA-12899 senza l'ALLARGAMENTO. MODIFY su
-- una colonna gia' popolata e' sicuro qui: si allarga solo, nessun valore
-- esistente supera 10 caratteri quindi nessun troncamento in gioco.
--------------------------------------------------------------------------------

ALTER TABLE T_BATCH_CARICAMENTO MODIFY ESITO VARCHAR2(20);

ALTER TABLE T_BATCH_CARICAMENTO DROP CONSTRAINT CK_BATCH_ESITO;

ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_ESITO CHECK (
  ESITO IS NULL OR ESITO IN ('IN_CORSO','OK','KO','PARZIALE','ERRORE_TECNICO')
);
