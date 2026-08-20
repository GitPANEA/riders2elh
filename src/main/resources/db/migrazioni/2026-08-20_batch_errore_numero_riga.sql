--------------------------------------------------------------------------------
-- Migrazione 2026-08-20 — tracciamento anomalie: posizione del record nel batch.
--
-- CHIAVE_BUSINESS da sola non basta a individuare il record anomalo quando e'
-- essa stessa mancante o malformata (es. id_rider assente): NUMERO_RIGA da un
-- riferimento indipendente alla posizione nel payload originale (0-based).
--
-- Colonna NULLABLE: le righe di errore gia storicizzate restano valide con
-- NULL, coerentemente col modello append-only — nessun backfill.
--
-- ADD di colonna nullable e' istantaneo, non richiede riscrittura dei segmenti.
--------------------------------------------------------------------------------

ALTER TABLE T_BATCH_CARICAMENTO_ERRORE ADD NUMERO_RIGA NUMBER;
