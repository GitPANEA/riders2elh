--------------------------------------------------------------------------------
-- 04 — Indici sulle colonne di foreign key e su DT_RICEZIONE
--
-- Nessuna delle FK "verso il batch" aveva un indice sulla colonna referenziante.
-- Due conseguenze, di peso molto diverso:
--
-- 1. PRESTAZIONI (il motivo reale). Tutti i repository interrogano per batch:
--      RiderAnagraficaRepository / VoceRepository / MovimentazioneRepository,
--      "WHERE ID_BATCH_CARICAMENTO = ? AND FLAG_ULTIMA_VERSIONE = 'S' ..."
--    Senza indice ogni chiamata e un full scan della tabella storicizzata, che
--    cresce in modo monotono per costruzione (append-only): il costo peggiora ad
--    ogni caricamento e non si stabilizza mai.
--
-- 2. LOCKING. In Oracle una FK non indicizzata fa acquisire un lock di tabella
--    sul figlio durante DELETE o UPDATE della chiave sul padre. Qui e teorico —
--    il modello non prevede DELETE fisici — ma diventa reale al primo intervento
--    manutentivo sul padre (purge, DROP PARTITION, correzione amministrativa).
--
-- Gli indici sulle tabelle partizionate sono LOCAL, coerentemente con gli altri
-- indici non univoci del DDL (IX_RIDER_ANAG_RIDER, IX_MOV_*): la colonna di
-- partizionamento non fa parte della chiave, ma un indice locale resta valido
-- dopo le operazioni di partizione e non richiede il REBUILD globale citato
-- nella sezione 6 di 00-ddl_riderpay.sql.
--
-- FLAG_ULTIMA_VERSIONE e in seconda posizione perche i tre predicati
-- applicativi filtrano sempre su entrambe le colonne: l'indice copre da solo il
-- filtro, senza accesso alla tabella per scartare le versioni storiche.
--
-- T_VOCE_ST non e partizionata (basso volume), quindi il suo indice non e LOCAL.
--------------------------------------------------------------------------------

CREATE INDEX IX_ANAG_BATCH ON T_RIDER_ANAGRAFICA_ST (ID_BATCH_CARICAMENTO, FLAG_ULTIMA_VERSIONE) LOCAL;

CREATE INDEX IX_MOV_BATCH  ON T_MOVIMENTAZIONE_ST   (ID_BATCH_CARICAMENTO, FLAG_ULTIMA_VERSIONE) LOCAL;

CREATE INDEX IX_VOCE_BATCH ON T_VOCE_ST             (ID_BATCH_CARICAMENTO, FLAG_ULTIMA_VERSIONE);

-- FK dalla tabella degli errori verso il batch: usata per elencare gli errori di
-- un caricamento, e il caso di locking descritto sopra.
CREATE INDEX IX_BATCH_ERRORE_BATCH ON T_BATCH_CARICAMENTO_ERRORE (ID_BATCH);

-- Autoreferenza T_BATCH_CARICAMENTO.ID_BATCH_RIFERIMENTO -> ID_BATCH: colonna
-- popolata solo su RETTIFICA e ANNULLAMENTO, quindi NULL sulla grande maggioranza
-- delle righe. Gli indici B-tree Oracle non indicizzano le chiavi interamente
-- NULL: l'indice contiene solo le righe di rettifica/annullamento e resta
-- piccolo, il che lo rende adatto proprio alle query di audit che le cercano.
CREATE INDEX IX_BATCH_RIFERIMENTO ON T_BATCH_CARICAMENTO (ID_BATCH_RIFERIMENTO);

-- Range scan di GET /api/v1/batch?dataInizio=...&dataFine=...
-- Il filtro applicativo e volutamente sargable — "DT_RICEZIONE >= ? AND
-- DT_RICEZIONE < ?", non "TRUNC(DT_RICEZIONE) BETWEEN ? AND ?" — proprio per
-- poter usare questo indice (vedi il javadoc di
-- BatchCaricamentoRepository.elenca). TIPO_OPERAZIONE come seconda colonna
-- perche e l'altro filtro dello stesso endpoint, combinabile con le date:
-- l'ordine (data, tipo) serve sia la ricerca per solo intervallo che quella per
-- intervallo + tipo, mentre l'ordine inverso non servirebbe la prima.
CREATE INDEX IX_BATCH_DT_RICEZIONE ON T_BATCH_CARICAMENTO (DT_RICEZIONE, TIPO_OPERAZIONE);

-- Raccolta statistiche: senza, l'optimizer puo ignorare gli indici appena creati
-- fino al successivo job automatico di gather.
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'T_RIDER_ANAGRAFICA_ST', CASCADE => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'T_MOVIMENTAZIONE_ST',   CASCADE => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'T_VOCE_ST',             CASCADE => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'T_BATCH_CARICAMENTO',   CASCADE => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'T_BATCH_CARICAMENTO_ERRORE', CASCADE => TRUE);
END;
/

-- Verifica: i sei indici devono comparire con STATUS = VALID (indici non
-- partizionati) oppure N/A (indici LOCAL, il cui stato e per partizione in
-- USER_IND_PARTITIONS).
SELECT INDEX_NAME, TABLE_NAME, PARTITIONED, STATUS
  FROM USER_INDEXES
 WHERE INDEX_NAME IN ('IX_ANAG_BATCH', 'IX_MOV_BATCH', 'IX_VOCE_BATCH',
                      'IX_BATCH_ERRORE_BATCH', 'IX_BATCH_RIFERIMENTO',
                      'IX_BATCH_DT_RICEZIONE')
 ORDER BY TABLE_NAME, INDEX_NAME;
