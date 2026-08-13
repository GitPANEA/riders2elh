--------------------------------------------------------------------------------
-- RiderPay — script DDL completo (Oracle 19c+)
-- Ordine di creazione: tabelle di log batch, master leggeri, tabelle storiche
-- (anagrafica, voci, movimentazioni + dettaglio), viste, indici univoci di
-- concorrenza. Corrisponde esattamente a §4-§7 e §12.1 del documento di
-- progettazione (progettazione_ingestion_oracle.md).
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- 1. Log ingestioni (batch) — §4
--------------------------------------------------------------------------------

CREATE TABLE T_BATCH_CARICAMENTO (
  ID_BATCH              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  TIPO_ENTITA           VARCHAR2(30)  NOT NULL,   -- ANAGRAFICA | VOCE | MOVIMENTAZIONE
  TIPO_OPERAZIONE       VARCHAR2(20)  DEFAULT 'CARICAMENTO' NOT NULL
                          CHECK (TIPO_OPERAZIONE IN ('CARICAMENTO','RETTIFICA','ANNULLAMENTO')),
  ID_BATCH_RIFERIMENTO  NUMBER REFERENCES T_BATCH_CARICAMENTO(ID_BATCH),
                        -- popolato solo per RETTIFICA/ANNULLAMENTO: batch originale corretto/annullato
  MOTIVO_OPERAZIONE     VARCHAR2(4000),
                        -- obbligatorio (a livello applicativo) per RETTIFICA/ANNULLAMENTO
  NOME_FILE_ORIGINE     VARCHAR2(255),
  FORMATO_FILE          VARCHAR2(10),              -- JSON | CSV
  CLIENT_ID             VARCHAR2(100),              -- sistema/utente chiamante
  DT_RICEZIONE          TIMESTAMP NOT NULL,          -- quando l API ha ricevuto la request
  DT_INIZIO_ELABORAZIONE TIMESTAMP,
  DT_FINE_ELABORAZIONE  TIMESTAMP,
  ESITO                 VARCHAR2(10),               -- OK | KO | PARZIALE
  NUM_RECORD_TOTALI     NUMBER,
  NUM_RECORD_OK         NUMBER,
  NUM_RECORD_KO         NUMBER,
  CHECKSUM_FILE         VARCHAR2(64),               -- sha256 del payload, per individuare re-invii identici
  NOTE                  VARCHAR2(4000)
);

CREATE TABLE T_BATCH_CARICAMENTO_ERRORE (
  ID_BATCH_ERRORE  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_BATCH         NUMBER NOT NULL REFERENCES T_BATCH_CARICAMENTO(ID_BATCH),
  CHIAVE_BUSINESS  VARCHAR2(200),      -- es. id_rider o id_movimentazione del record fallito
  MESSAGGIO_ERRORE VARCHAR2(4000),
  PAYLOAD_JSON     CLOB,               -- record originale, per poter fare replay/debug
  DT_INSERIMENTO   TIMESTAMP DEFAULT SYSTIMESTAMP
);

--------------------------------------------------------------------------------
-- 2. Tabelle master leggere (target reale delle FK) — §7
--------------------------------------------------------------------------------

CREATE TABLE T_RIDER (
  ID_RIDER           VARCHAR2(40) PRIMARY KEY,
  DT_PRIMA_COMPARSA  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE T_VOCE (
  ID_VOCE            VARCHAR2(60) PRIMARY KEY,
  DT_PRIMA_COMPARSA  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

--------------------------------------------------------------------------------
-- 3. Anagrafica rider — storicizzata, partizionata — §5
--------------------------------------------------------------------------------

CREATE TABLE T_RIDER_ANAGRAFICA_ST (
  ID_ANAGRAFICA_ST        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_RIDER               VARCHAR2(40)  NOT NULL,   -- chiave di business dalla fonte
  REGIME_FISCALE         VARCHAR2(40)  NOT NULL,   -- PRESTAZIONE_OCCASIONALE | REGIME_FORFETTARIO | REGIME_ORDINARIO
  DATA_INIZIO_VALIDITA   DATE          NOT NULL,
  DATA_FINE_VALIDITA     DATE,
  NOME                   VARCHAR2(100) NOT NULL,
  COGNOME                VARCHAR2(100) NOT NULL,
  CODICE_FISCALE         VARCHAR2(16)  NOT NULL,
  PARTITA_IVA            VARCHAR2(11),             -- assente per PRESTAZIONE_OCCASIONALE
  TELEFONO_CELLULARE     VARCHAR2(20),
  EMAIL                  VARCHAR2(200),
  INDIRIZZO_RESIDENZA    VARCHAR2(200),
  CODICE_ISTAT_RESIDENZA VARCHAR2(10),
  COMUNE_RESIDENZA       VARCHAR2(100),
  PROVINCIA_RESIDENZA    VARCHAR2(2),
  CAP_RESIDENZA          VARCHAR2(5),
  ID_BATCH_CARICAMENTO   NUMBER NOT NULL REFERENCES T_BATCH_CARICAMENTO(ID_BATCH),
  DT_INSERIMENTO         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  FLAG_ULTIMA_VERSIONE   CHAR(1) DEFAULT 'S' NOT NULL CHECK (FLAG_ULTIMA_VERSIONE IN ('S','N')),
  STATO_RECORD           VARCHAR2(10) DEFAULT 'ATTIVO' NOT NULL CHECK (STATO_RECORD IN ('ATTIVO','ANNULLATO'))
)
PARTITION BY RANGE (DT_INSERIMENTO)
INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))
(
  PARTITION P_ANAG_INIZIALE VALUES LESS THAN (TIMESTAMP '2026-01-01 00:00:00')
);

CREATE INDEX IX_RIDER_ANAG_RIDER ON T_RIDER_ANAGRAFICA_ST (ID_RIDER, FLAG_ULTIMA_VERSIONE) LOCAL;

CREATE VIEW VW_RIDER_ANAGRAFICA_CORRENTE AS
SELECT * FROM T_RIDER_ANAGRAFICA_ST WHERE FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO';

--------------------------------------------------------------------------------
-- 4. Dizionario voci — storicizzato, non partizionato (basso volume) — §6
--------------------------------------------------------------------------------

CREATE TABLE T_VOCE_ST (
  ID_VOCE_ST                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_VOCE                      VARCHAR2(60) NOT NULL,   -- es. INDENNITA_FESTIVI, PROSPETTO_TOTALE
  DESCRIZIONE                  VARCHAR2(400) NOT NULL,
  MESE_RIFERIMENTO_RICHIESTO   CHAR(1) NOT NULL CHECK (MESE_RIFERIMENTO_RICHIESTO IN ('S','N')),
  ID_BATCH_CARICAMENTO         NUMBER NOT NULL REFERENCES T_BATCH_CARICAMENTO(ID_BATCH),
  DT_INSERIMENTO                TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  FLAG_ULTIMA_VERSIONE         CHAR(1) DEFAULT 'S' NOT NULL CHECK (FLAG_ULTIMA_VERSIONE IN ('S','N')),
  STATO_RECORD                 VARCHAR2(10) DEFAULT 'ATTIVO' NOT NULL CHECK (STATO_RECORD IN ('ATTIVO','ANNULLATO'))
);

CREATE INDEX IX_VOCE_ID ON T_VOCE_ST (ID_VOCE, FLAG_ULTIMA_VERSIONE);

CREATE VIEW VW_VOCE_CORRENTE AS
SELECT * FROM T_VOCE_ST WHERE FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO';

--------------------------------------------------------------------------------
-- 5. Movimentazioni — header storicizzato, partizionato — §7
--------------------------------------------------------------------------------

CREATE TABLE T_MOVIMENTAZIONE_ST (
  ID_MOVIMENTAZIONE_ST              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_MOVIMENTAZIONE                VARCHAR2(100) NOT NULL,  -- chiave di business dalla fonte
  ID_RIDER                         VARCHAR2(40)  NOT NULL,
  PERIODO_DA                       DATE NOT NULL,
  PERIODO_A                        DATE NOT NULL,

  -- totali_consegne
  TOT_NUMERO_CONSEGNE              NUMBER,
  TOT_CONSEGNE_LORDO               NUMBER(12,2),

  -- totali_modifiche_integrazioni
  TOT_MODIFICHE_IMPORTO_LORDO      NUMBER(12,2),
  TOT_MODIFICHE_RITENUTA_PERC      NUMBER(5,2),
  TOT_MODIFICHE_RITENUTA_IMPORTO   NUMBER(12,2),
  TOT_MODIFICHE_IVA_PERC           NUMBER(5,2),
  TOT_MODIFICHE_IVA_IMPORTO        NUMBER(12,2),
  TOT_MODIFICHE_TOTALE             NUMBER(12,2),

  -- riepilogo
  IMPOSTA_BOLLO                    NUMBER(12,2),
  PERC_TRATTENUTE_FISCALI          NUMBER(5,2),
  IMPORTO_TRATTENUTE_FISCALI       NUMBER(12,2),
  PERC_TRATTENUTE_PREVIDENZIALI    NUMBER(5,2),
  IMPORTO_TRATTENUTE_PREVIDENZIALI NUMBER(12,2),
  PAGAMENTI_CONTANTI_GIA_RISCOSSI  NUMBER(12,2),
  TOTALE_DOVUTO                    NUMBER(12,2),

  ID_BATCH_CARICAMENTO              NUMBER NOT NULL REFERENCES T_BATCH_CARICAMENTO(ID_BATCH),
  DT_INSERIMENTO                    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  FLAG_ULTIMA_VERSIONE              CHAR(1) DEFAULT 'S' NOT NULL CHECK (FLAG_ULTIMA_VERSIONE IN ('S','N')),
  STATO_RECORD                      VARCHAR2(10) DEFAULT 'ATTIVO' NOT NULL CHECK (STATO_RECORD IN ('ATTIVO','ANNULLATO')),

  CONSTRAINT FK_MOV_RIDER FOREIGN KEY (ID_RIDER) REFERENCES T_RIDER(ID_RIDER)
)
PARTITION BY RANGE (DT_INSERIMENTO)
INTERVAL (NUMTOYMINTERVAL(1,'MONTH'))
(
  PARTITION P_MOV_INIZIALE VALUES LESS THAN (TIMESTAMP '2026-01-01 00:00:00')
);

CREATE INDEX IX_MOV_ID_MOVIMENTAZIONE ON T_MOVIMENTAZIONE_ST (ID_MOVIMENTAZIONE, FLAG_ULTIMA_VERSIONE) LOCAL;
CREATE INDEX IX_MOV_RIDER_PERIODO ON T_MOVIMENTAZIONE_ST (ID_RIDER, PERIODO_DA, PERIODO_A) LOCAL;

--------------------------------------------------------------------------------
-- 5.1 Movimentazioni — dettaglio consegne giornaliere, partizionata per riferimento — §7.1
--------------------------------------------------------------------------------

CREATE TABLE T_MOVIMENTAZIONE_CONSEGNA_ST (
  ID_CONSEGNA_ST          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_MOVIMENTAZIONE_ST    NUMBER NOT NULL,
  DATA_CONSEGNA          DATE NOT NULL,
  NUMERO_CONSEGNE        NUMBER NOT NULL,
  TOTALE_PARZIALE_LORDO  NUMBER(12,2) NOT NULL,
  CONSTRAINT FK_CONSEGNA_MOV FOREIGN KEY (ID_MOVIMENTAZIONE_ST)
    REFERENCES T_MOVIMENTAZIONE_ST(ID_MOVIMENTAZIONE_ST)
)
PARTITION BY REFERENCE (FK_CONSEGNA_MOV);

CREATE INDEX IX_CONSEGNA_MOV ON T_MOVIMENTAZIONE_CONSEGNA_ST (ID_MOVIMENTAZIONE_ST, DATA_CONSEGNA) LOCAL;

--------------------------------------------------------------------------------
-- 5.2 Movimentazioni — dettaglio voci (modifiche/integrazioni + prospetto finale) — §7.2
--------------------------------------------------------------------------------

CREATE TABLE T_MOVIMENTAZIONE_VOCE_ST (
  ID_MOV_VOCE_ST          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ID_MOVIMENTAZIONE_ST    NUMBER NOT NULL,
  TIPO_SEZIONE           VARCHAR2(30) NOT NULL CHECK (TIPO_SEZIONE IN ('MODIFICA_INTEGRAZIONE','PROSPETTO_FINALE')),
  ID_VOCE                VARCHAR2(60) NOT NULL REFERENCES T_VOCE(ID_VOCE),
  MESE_RIFERIMENTO       VARCHAR2(7),               -- formato YYYY-MM, solo se la voce lo richiede
  IMPORTO_LORDO          NUMBER(12,2),
  RITENUTA_PERCENTUALE   NUMBER(5,2),
  RITENUTA_IMPORTO       NUMBER(12,2),
  IVA_PERCENTUALE        NUMBER(5,2),
  IVA_IMPORTO            NUMBER(12,2),
  TOTALE                 NUMBER(12,2),
  CONSTRAINT FK_MOVVOCE_MOV FOREIGN KEY (ID_MOVIMENTAZIONE_ST)
    REFERENCES T_MOVIMENTAZIONE_ST(ID_MOVIMENTAZIONE_ST)
)
PARTITION BY REFERENCE (FK_MOVVOCE_MOV);

CREATE INDEX IX_MOVVOCE_MOV ON T_MOVIMENTAZIONE_VOCE_ST (ID_MOVIMENTAZIONE_ST, TIPO_SEZIONE) LOCAL;
CREATE INDEX IX_MOVVOCE_VOCE ON T_MOVIMENTAZIONE_VOCE_ST (ID_VOCE) LOCAL;

--------------------------------------------------------------------------------
-- 5.3 Vista "stato corrente" per i consumer applicativi — §7.3
--------------------------------------------------------------------------------

CREATE VIEW VW_MOVIMENTAZIONE_CORRENTE AS
SELECT * FROM T_MOVIMENTAZIONE_ST WHERE FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO';

--------------------------------------------------------------------------------
-- 6. Indici univoci di concorrenza — §12.1
--
-- Garantiscono che esista al massimo UNA riga con FLAG_ULTIMA_VERSIONE = S per
-- ciascuna chiave di business (i NULL, cioè le righe non correnti, sono esclusi
-- automaticamente dall unicità). Sono GLOBAL perché la chiave di unicità non
-- include la colonna di partizionamento (DT_INSERIMENTO): dopo un MOVE/DROP
-- PARTITION sulle tabelle padre può servire un ALTER INDEX ... REBUILD.
--------------------------------------------------------------------------------

CREATE UNIQUE INDEX UX_ANAG_CORRENTE ON T_RIDER_ANAGRAFICA_ST
  (CASE WHEN FLAG_ULTIMA_VERSIONE = 'S' THEN ID_RIDER END);

CREATE UNIQUE INDEX UX_VOCE_CORRENTE ON T_VOCE_ST
  (CASE WHEN FLAG_ULTIMA_VERSIONE = 'S' THEN ID_VOCE END);

CREATE UNIQUE INDEX UX_MOV_CORRENTE ON T_MOVIMENTAZIONE_ST
  (CASE WHEN FLAG_ULTIMA_VERSIONE = 'S' THEN ID_MOVIMENTAZIONE END);

--------------------------------------------------------------------------------
-- 8. Client OAuth2 (client_credentials) — sicurezza API
--
-- Registrazione dei client autorizzati a chiamare le API. Nessun endpoint
-- self-service: un nuovo client si registra con un INSERT amministrativo,
-- con CLIENT_SECRET_HASH gia calcolato in bcrypt (mai il secret in chiaro).
-- SCOPE_CONCESSI e una stringa di scope separati da spazio (pattern OAuth2
-- standard); un solo scope generico "api.riderpay" per ora.
--------------------------------------------------------------------------------

CREATE TABLE T_CLIENT_OAUTH (
  ID_CLIENT_OAUTH      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  CLIENT_ID             VARCHAR2(100)  NOT NULL,
  CLIENT_SECRET_HASH    VARCHAR2(255)  NOT NULL,
  DESCRIZIONE           VARCHAR2(255),
  SCOPE_CONCESSI        VARCHAR2(500)  DEFAULT 'api.riderpay' NOT NULL,
  TOKEN_TTL_SECONDI     NUMBER         DEFAULT 3600 NOT NULL,
  FLAG_ATTIVO           CHAR(1)        DEFAULT 'S' NOT NULL CHECK (FLAG_ATTIVO IN ('S','N')),
  DT_CREAZIONE          TIMESTAMP      DEFAULT SYSTIMESTAMP NOT NULL,
  DT_ULTIMA_MODIFICA    TIMESTAMP,
  DT_ULTIMO_UTILIZZO    TIMESTAMP
);

CREATE UNIQUE INDEX UX_CLIENT_OAUTH_CLIENT_ID ON T_CLIENT_OAUTH (CLIENT_ID);

-- Client di test per Postman. Secret in chiaro (solo per ambiente di sviluppo):
-- -ovci-UPmmduwbc0nL3ta-lkzsZt9PhpUJ4TckxVp0E
INSERT INTO T_CLIENT_OAUTH (CLIENT_ID, CLIENT_SECRET_HASH, DESCRIZIONE, SCOPE_CONCESSI)
VALUES ('riders2elh-test', '$2a$10$WovhDdfd7rxAnidg1ereTelEWu65tx5ZofukAK2KssSyrYsMyzTKG', 'Client di test per la collection Postman', 'api.riderpay');

--------------------------------------------------------------------------------
-- 9. Permessi applicativi (esempio) — §11 "DELETE è sempre logico"
--
-- L utenza usata dalle API deve poter fare solo INSERT + l UPDATE della singola
-- colonna FLAG_ULTIMA_VERSIONE: nessun DELETE fisico, nessun UPDATE libero.
-- Adattare RIDERPAY_APP al nome reale dell utenza applicativa.
--------------------------------------------------------------------------------

-- GRANT SELECT, INSERT ON T_BATCH_CARICAMENTO           TO RIDERPAY_APP;
-- GRANT SELECT, INSERT ON T_BATCH_CARICAMENTO_ERRORE     TO RIDERPAY_APP;
-- GRANT SELECT, INSERT ON T_RIDER                        TO RIDERPAY_APP;
-- GRANT SELECT, INSERT ON T_VOCE                         TO RIDERPAY_APP;
-- GRANT SELECT, INSERT, UPDATE (FLAG_ULTIMA_VERSIONE) ON T_RIDER_ANAGRAFICA_ST      TO RIDERPAY_APP;
-- GRANT SELECT, INSERT, UPDATE (FLAG_ULTIMA_VERSIONE) ON T_VOCE_ST                  TO RIDERPAY_APP;
-- GRANT SELECT, INSERT, UPDATE (FLAG_ULTIMA_VERSIONE) ON T_MOVIMENTAZIONE_ST        TO RIDERPAY_APP;
-- GRANT SELECT, INSERT ON T_MOVIMENTAZIONE_CONSEGNA_ST   TO RIDERPAY_APP;
-- GRANT SELECT, INSERT ON T_MOVIMENTAZIONE_VOCE_ST       TO RIDERPAY_APP;
-- GRANT SELECT ON VW_RIDER_ANAGRAFICA_CORRENTE           TO RIDERPAY_APP;
-- GRANT SELECT ON VW_VOCE_CORRENTE                       TO RIDERPAY_APP;
-- GRANT SELECT ON VW_MOVIMENTAZIONE_CORRENTE             TO RIDERPAY_APP;
-- GRANT SELECT ON T_CLIENT_OAUTH                         TO RIDERPAY_APP;
