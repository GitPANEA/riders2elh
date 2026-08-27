--------------------------------------------------------------------------------
-- Pulizia fisica dei dati di uno o più caricamenti di test (es. docs/v2/*.json)
-- identificati per ID_BATCH_CARICAMENTO.
--
-- ATTENZIONE: questo script fa DELETE fisiche, in contrasto con il modello
-- append-only descritto in CLAUDE.md. Va usato SOLO per ripulire dati di
-- test/collaudo dal DB di sviluppo, MAI su un ambiente con dati reali che
-- devono restare storicizzati per audit. Non esiste rollback applicativo:
-- una volta committato, il dato è perso definitivamente.
--
-- Uso:
--   1. Valorizzare la lista di ID_BATCH da eliminare nella variabile bind
--      sotto (sezione "PARAMETRI").
--   2. Eseguire prima la sezione "0. VERIFICA" e controllare i conteggi.
--   3. Eseguire la sezione "1. DELETE" per intero, in una singola
--      transazione (NON autocommit) — poi COMMIT o ROLLBACK a mano dopo
--      aver controllato l'esito.
--
-- Nota sulle master condivise (T_RIDER, T_VOCE): vengono eliminate SOLO se,
-- dopo le DELETE sulle tabelle storicizzate, non restano più righe che le
-- referenziano — cioè solo se quel rider/voce esisteva unicamente per
-- effetto di questo caricamento.
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- PARAMETRI: elencare qui gli ID_BATCH da rimuovere
--------------------------------------------------------------------------------
DEFINE lista_batch = '&&lista_id_batch';
-- esempio d'uso da SQL*Plus/SQLcl:
--   @99-cleanup-batch-test.sql
--   Inserire lista_id_batch: 101,102

--------------------------------------------------------------------------------
-- 0. VERIFICA (sola lettura) — eseguire ed esaminare PRIMA di procedere
--------------------------------------------------------------------------------

SELECT ID_BATCH, TIPO_ENTITA, TIPO_OPERAZIONE, ESITO, DT_RICEZIONE,
       NUM_RECORD_TOTALI, NUM_RECORD_OK, NUM_RECORD_KO
FROM T_BATCH_CARICAMENTO
WHERE ID_BATCH IN (&&lista_batch);

-- eventuali batch di rettifica/annullamento che puntano a uno dei batch da
-- eliminare: se presenti e NON inclusi nella lista, van decisi a parte,
-- perché la FK ID_BATCH_RIFERIMENTO impedirebbe comunque la DELETE del
-- batch originale finché esistono
SELECT ID_BATCH, ID_BATCH_RIFERIMENTO, TIPO_OPERAZIONE
FROM T_BATCH_CARICAMENTO
WHERE ID_BATCH_RIFERIMENTO IN (&&lista_batch)
  AND ID_BATCH NOT IN (&&lista_batch);

SELECT COUNT(*) AS n_errori FROM T_BATCH_CARICAMENTO_ERRORE WHERE ID_BATCH IN (&&lista_batch);
SELECT COUNT(*) AS n_anagrafiche FROM T_RIDER_ANAGRAFICA_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);
SELECT COUNT(*) AS n_voci_dizionario FROM T_VOCE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);
SELECT COUNT(*) AS n_movimentazioni FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);
SELECT COUNT(*) AS n_consegne
FROM T_MOVIMENTAZIONE_CONSEGNA_ST
WHERE ID_MOVIMENTAZIONE_ST IN (
  SELECT ID_MOVIMENTAZIONE_ST FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch)
);
SELECT COUNT(*) AS n_movimentazione_voci
FROM T_MOVIMENTAZIONE_VOCE_ST
WHERE ID_MOVIMENTAZIONE_ST IN (
  SELECT ID_MOVIMENTAZIONE_ST FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch)
);

-- rider che risulterebbero orfani (esistono SOLO grazie a questo caricamento)
SELECT COUNT(*) AS n_rider_orfani
FROM T_RIDER r
WHERE EXISTS (
        SELECT 1 FROM T_RIDER_ANAGRAFICA_ST a
        WHERE a.ID_RIDER = r.ID_RIDER AND a.ID_BATCH_CARICAMENTO IN (&&lista_batch)
      )
  AND NOT EXISTS (
        SELECT 1 FROM T_RIDER_ANAGRAFICA_ST a2
        WHERE a2.ID_RIDER = r.ID_RIDER AND a2.ID_BATCH_CARICAMENTO NOT IN (&&lista_batch)
      )
  AND NOT EXISTS (
        SELECT 1 FROM T_MOVIMENTAZIONE_ST m
        WHERE m.ID_RIDER = r.ID_RIDER AND m.ID_BATCH_CARICAMENTO NOT IN (&&lista_batch)
      );

--------------------------------------------------------------------------------
-- 1. DELETE — eseguire come blocco unico, poi COMMIT / ROLLBACK a mano
--------------------------------------------------------------------------------

-- 1.1 dettaglio movimentazioni (dipendono da T_MOVIMENTAZIONE_ST del batch)
DELETE FROM T_MOVIMENTAZIONE_VOCE_ST
WHERE ID_MOVIMENTAZIONE_ST IN (
  SELECT ID_MOVIMENTAZIONE_ST FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch)
);

DELETE FROM T_MOVIMENTAZIONE_CONSEGNA_ST
WHERE ID_MOVIMENTAZIONE_ST IN (
  SELECT ID_MOVIMENTAZIONE_ST FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch)
);

-- 1.2 header movimentazioni
DELETE FROM T_MOVIMENTAZIONE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);

-- 1.3 anagrafiche e dizionario voci
DELETE FROM T_RIDER_ANAGRAFICA_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);
DELETE FROM T_VOCE_ST WHERE ID_BATCH_CARICAMENTO IN (&&lista_batch);

-- 1.4 master orfane (solo rider/voci che non esistono più per altri batch)
DELETE FROM T_RIDER r
WHERE NOT EXISTS (SELECT 1 FROM T_RIDER_ANAGRAFICA_ST a WHERE a.ID_RIDER = r.ID_RIDER)
  AND NOT EXISTS (SELECT 1 FROM T_MOVIMENTAZIONE_ST m WHERE m.ID_RIDER = r.ID_RIDER);

DELETE FROM T_VOCE v
WHERE NOT EXISTS (SELECT 1 FROM T_VOCE_ST vs WHERE vs.ID_VOCE = v.ID_VOCE)
  AND NOT EXISTS (SELECT 1 FROM T_MOVIMENTAZIONE_VOCE_ST mv WHERE mv.ID_VOCE = v.ID_VOCE);

-- 1.5 log errori e batch stessi (ultimo, per via delle FK)
DELETE FROM T_BATCH_CARICAMENTO_ERRORE WHERE ID_BATCH IN (&&lista_batch);
DELETE FROM T_BATCH_CARICAMENTO WHERE ID_BATCH IN (&&lista_batch);

-- COMMIT;
-- ROLLBACK;
