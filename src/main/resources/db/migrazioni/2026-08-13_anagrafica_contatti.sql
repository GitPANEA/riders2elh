--------------------------------------------------------------------------------
-- Migrazione 2026-08-13 — TFN-1166: due nuovi campi di contatto in anagrafica.
--
-- Entrambe le colonne sono NULLABLE: le versioni gia storicizzate in
-- T_RIDER_ANAGRAFICA_ST restano valide con NULL (il dato non era noto quando
-- sono state scritte), coerentemente con il modello append-only — non si fa
-- backfill delle righe storiche.
--
-- ADD di colonne nullable non richiede riscrittura dei segmenti: e istantaneo
-- anche sulle partizioni gia materializzate.
--------------------------------------------------------------------------------

ALTER TABLE T_RIDER_ANAGRAFICA_ST ADD (
  TELEFONO_CELLULARE  VARCHAR2(20),
  EMAIL               VARCHAR2(200)
);

-- VW_RIDER_ANAGRAFICA_CORRENTE e definita come SELECT *: va ricompilata per
-- esporre le nuove colonne (una vista con * "congela" la lista al momento
-- della creazione).
CREATE OR REPLACE VIEW VW_RIDER_ANAGRAFICA_CORRENTE AS
SELECT * FROM T_RIDER_ANAGRAFICA_ST WHERE FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO';
