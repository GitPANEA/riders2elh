--------------------------------------------------------------------------------
-- 03 — Foreign key mancanti verso le tabelle master (T_RIDER, T_VOCE)
--
-- Il DDL descrive T_RIDER e T_VOCE come "target reale delle FK" (sezione 2 di
-- 00-ddl_riderpay.sql), ma solo due delle quattro referenze erano vincolate:
--
--   T_MOVIMENTAZIONE_ST.ID_RIDER      -> FK_MOV_RIDER          (gia presente)
--   T_MOVIMENTAZIONE_VOCE_ST.ID_VOCE  -> FK inline             (gia presente)
--   T_RIDER_ANAGRAFICA_ST.ID_RIDER    -> nessun vincolo        <- questo script
--   T_VOCE_ST.ID_VOCE                 -> nessun vincolo        <- questo script
--
-- Le master erano quindi popolate per sola convenzione applicativa
-- (MasterKeyRepository.assicuraRider / assicuraVoce, chiamati da
-- AnagraficaService e VoceService prima di ogni insert storicizzato): un
-- caricamento eseguito fuori dall'applicazione — script di fix, correzione
-- manuale, import una tantum — poteva inserire una versione di anagrafica per un
-- ID_RIDER assente da T_RIDER, privando la master del suo unico scopo, essere
-- l'elenco autoritativo delle chiavi di business.
--
-- ATTENZIONE — su ambiente gia popolato eseguire PRIMA i due controlli di
-- orfani qui sotto: un solo valore non presente in master fa fallire l'ALTER
-- con ORA-02298 (parent keys not found), lasciando la tabella invariata.
-- In quel caso popolare la master con gli ID mancanti (INSERT dal SELECT di
-- controllo) e ripetere l'ALTER: mai cancellare righe storiche per far passare
-- il vincolo, contraddirebbe il modello append-only.
--
-- Nota: queste sono FK ordinarie su tabella partizionata, NON un
-- PARTITION BY REFERENCE — non alterano in alcun modo il partizionamento di
-- T_RIDER_ANAGRAFICA_ST (RANGE/INTERVAL su DT_INSERIMENTO) e non richiedono
-- riscrittura dei segmenti.
--------------------------------------------------------------------------------

-- Controllo orfani 1: deve restituire 0 righe.
SELECT DISTINCT ID_RIDER AS RIDER_ORFANO
  FROM T_RIDER_ANAGRAFICA_ST
 WHERE ID_RIDER NOT IN (SELECT ID_RIDER FROM T_RIDER);

-- Controllo orfani 2: deve restituire 0 righe.
SELECT DISTINCT ID_VOCE AS VOCE_ORFANA
  FROM T_VOCE_ST
 WHERE ID_VOCE NOT IN (SELECT ID_VOCE FROM T_VOCE);

-- Rimedio, se i controlli sopra restituiscono righe: allineare la master
-- (DT_PRIMA_COMPARSA prende il DEFAULT SYSTIMESTAMP, che e la data di questa
-- migrazione e non quella della prima comparsa reale — la master registra
-- l'esistenza della chiave, non la sua storia).
--
-- INSERT INTO T_RIDER (ID_RIDER)
-- SELECT DISTINCT ID_RIDER FROM T_RIDER_ANAGRAFICA_ST
--  WHERE ID_RIDER NOT IN (SELECT ID_RIDER FROM T_RIDER);
--
-- INSERT INTO T_VOCE (ID_VOCE)
-- SELECT DISTINCT ID_VOCE FROM T_VOCE_ST
--  WHERE ID_VOCE NOT IN (SELECT ID_VOCE FROM T_VOCE);
--
-- COMMIT;

ALTER TABLE T_RIDER_ANAGRAFICA_ST ADD CONSTRAINT FK_ANAG_RIDER
  FOREIGN KEY (ID_RIDER) REFERENCES T_RIDER(ID_RIDER);

ALTER TABLE T_VOCE_ST ADD CONSTRAINT FK_VOCE_DIZIONARIO
  FOREIGN KEY (ID_VOCE) REFERENCES T_VOCE(ID_VOCE);

-- Verifica: le due constraint devono comparire con STATUS = ENABLED
-- e VALIDATED = VALIDATED.
SELECT CONSTRAINT_NAME, TABLE_NAME, STATUS, VALIDATED
  FROM USER_CONSTRAINTS
 WHERE CONSTRAINT_NAME IN ('FK_ANAG_RIDER', 'FK_VOCE_DIZIONARIO');
