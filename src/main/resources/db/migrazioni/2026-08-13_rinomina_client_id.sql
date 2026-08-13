--------------------------------------------------------------------------------
-- Migrazione 2026-08-13 — rinomina del client OAuth di test
-- riderpay-test -> riders2elh-test, a seguito del rename del progetto.
--
-- Il secret NON cambia: l'hash bcrypt in CLIENT_SECRET_HASH non dipende dal
-- CLIENT_ID, quindi resta valido e non va rigenerato nulla lato client.
--
-- Esiste UX_CLIENT_OAUTH_CLIENT_ID (univoco) su CLIENT_ID: l'UPDATE fallisce
-- con ORA-00001 se il nuovo valore e gia presente.
--------------------------------------------------------------------------------

UPDATE T_CLIENT_OAUTH
   SET CLIENT_ID          = 'riders2elh-test',
       DT_ULTIMA_MODIFICA = SYSTIMESTAMP
 WHERE CLIENT_ID = 'riderpay-test';

-- Deve riportare 1 riga aggiornata.

COMMIT;

--------------------------------------------------------------------------------
-- T_BATCH_CARICAMENTO NON viene toccata, deliberatamente.
--
-- CLIENT_ID registra chi ha caricato quel batch nel momento in cui e avvenuto:
-- riscriverlo contraddirebbe il modello append-only, dove le righe storiche
-- restano la fotografia di cio che si sapeva allora. I batch anteriori a questa
-- data continuano quindi a riportare 'riderpay-test' — e lo stesso client.
--
-- Se in futuro serve una lettura unificata dell'audit:
--
--   SELECT ... FROM T_BATCH_CARICAMENTO
--    WHERE CLIENT_ID IN ('riderpay-test', 'riders2elh-test');
--------------------------------------------------------------------------------
