--------------------------------------------------------------------------------
-- 05 — Vincoli CHECK di coerenza e di dominio
--
-- Il DDL originale documentava a commento diverse regole che nessun vincolo
-- imponeva. L'asimmetria era evidente: TIPO_OPERAZIONE, STATO_RECORD,
-- FLAG_ULTIMA_VERSIONE e TIPO_SEZIONE avevano tutti un CHECK, mentre
-- TIPO_ENTITA, ESITO e FORMATO_FILE — documentati nello stesso stile, con un
-- commento "-- OK | KO | PARZIALE" accanto alla colonna — no.
--
-- Tutti i vincoli qui sono coerenti con cio che l'applicazione scrive oggi:
-- i valori provengono dagli enum Java (TipoEntita, EsitoBatch, TipoOperazione,
-- StatoRecord) o da letterali fissi nei service, quindi su un ambiente
-- consistente l'ALTER passa senza ENABLE NOVALIDATE. Se un ALTER fallisce con
-- ORA-02293 significa che in tabella esistono righe non conformi: vanno
-- esaminate prima di forzare il vincolo — vedi la nota in fondo.
--
-- I CHECK sulle date sono gli unici che aggiungono una regola non presente in
-- nessuna forma: i DTO validano i campi singolarmente (@NotNull su periodo_da e
-- periodo_a) ma non la loro relazione, quindi un periodo invertito era accettato
-- e storicizzato senza obiezioni.
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
-- 1. T_BATCH_CARICAMENTO — coerenza tra tipo di operazione e campi correlati
--
-- Il DDL annotava ID_BATCH_RIFERIMENTO come "popolato solo per
-- RETTIFICA/ANNULLAMENTO" e MOTIVO_OPERAZIONE come "obbligatorio (a livello
-- applicativo)". Entrambe le regole sono effettivamente rispettate dal codice:
-- ogni chiamata a creaBatch con RETTIFICA o ANNULLAMENTO passa il batch di
-- riferimento e il motivo (AnagraficaService, VoceService,
-- MovimentazioneService, BatchQueryService.annullaBatch), e il motivo arriva da
-- MotivoRequest che e @NotBlank. Il CHECK rende strutturale una garanzia che
-- oggi dipende dal non sbagliare in quattro punti distinti.
--------------------------------------------------------------------------------

ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_COERENZA_OPERAZIONE CHECK (
     (TIPO_OPERAZIONE = 'CARICAMENTO'  AND ID_BATCH_RIFERIMENTO IS NULL)
  OR (TIPO_OPERAZIONE IN ('RETTIFICA','ANNULLAMENTO')
      AND ID_BATCH_RIFERIMENTO IS NOT NULL
      AND MOTIVO_OPERAZIONE   IS NOT NULL)
);

-- Un batch non puo riferire se stesso: l'autoreferenza deve puntare a un batch
-- precedente. Non impedisce un ciclo piu lungo (A -> B -> A), che un CHECK di
-- riga non puo vedere, ma copre il caso di gran lunga piu probabile.
ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_NON_AUTOREFERENTE CHECK (
  ID_BATCH_RIFERIMENTO IS NULL OR ID_BATCH_RIFERIMENTO <> ID_BATCH
);

--------------------------------------------------------------------------------
-- 2. T_BATCH_CARICAMENTO — domini enumerati mancanti
--
-- Nessun DEFAULT su queste colonne, quindi il vincolo ammette NULL dove il
-- codice lo scrive davvero:
--   ESITO        e NULL sui batch ancora aperti (valorizzato da chiudiBatch)
--   FORMATO_FILE e NULL su rettifiche e annullamenti (nessun file di origine)
--------------------------------------------------------------------------------

ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_TIPO_ENTITA CHECK (
  TIPO_ENTITA IN ('ANAGRAFICA','VOCE','MOVIMENTAZIONE')
);

ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_ESITO CHECK (
  ESITO IS NULL OR ESITO IN ('OK','KO','PARZIALE')
);

ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_FORMATO_FILE CHECK (
  FORMATO_FILE IS NULL OR FORMATO_FILE IN ('JSON','CSV')
);

-- Contatori: mai negativi, e la somma deve quadrare. Ammessi NULL perche
-- restano tali finche il batch e aperto (li scrive chiudiBatch, tutti insieme).
ALTER TABLE T_BATCH_CARICAMENTO ADD CONSTRAINT CK_BATCH_CONTATORI CHECK (
     (NUM_RECORD_TOTALI IS NULL AND NUM_RECORD_OK IS NULL AND NUM_RECORD_KO IS NULL)
  OR (NUM_RECORD_TOTALI >= 0 AND NUM_RECORD_OK >= 0 AND NUM_RECORD_KO >= 0
      AND NUM_RECORD_TOTALI = NUM_RECORD_OK + NUM_RECORD_KO)
);

--------------------------------------------------------------------------------
-- 3. Coerenza temporale — regole che nessuna validazione applicativa copre
--------------------------------------------------------------------------------

-- Periodo di una movimentazione: l'estremo finale non puo precedere l'iniziale.
-- Entrambe le colonne sono NOT NULL, nessun ramo NULL da ammettere.
ALTER TABLE T_MOVIMENTAZIONE_ST ADD CONSTRAINT CK_MOV_PERIODO CHECK (
  PERIODO_A >= PERIODO_DA
);

-- Validita di una versione di anagrafica. DATA_FINE_VALIDITA e nullable
-- (validita aperta), DATA_INIZIO_VALIDITA e NOT NULL.
ALTER TABLE T_RIDER_ANAGRAFICA_ST ADD CONSTRAINT CK_ANAG_VALIDITA CHECK (
  DATA_FINE_VALIDITA IS NULL OR DATA_FINE_VALIDITA >= DATA_INIZIO_VALIDITA
);

--------------------------------------------------------------------------------
-- 4. Formato di MESE_RIFERIMENTO
--
-- Il DDL documentava "formato 'YYYY-MM', solo se la voce lo richiede" senza
-- imporlo: la colonna e VARCHAR2(7) e accettava qualunque stringa di 7
-- caratteri. Resta nullable, perche il mese si popola solo per le voci con
-- MESE_RIFERIMENTO_RICHIESTO = 'S'.
--
-- Il vincolo verifica anche il mese (01-12), non solo la forma: '2026-13'
-- passerebbe un controllo di sola lunghezza.
--------------------------------------------------------------------------------

ALTER TABLE T_MOVIMENTAZIONE_VOCE_ST ADD CONSTRAINT CK_MOVVOCE_MESE_RIFERIMENTO CHECK (
  MESE_RIFERIMENTO IS NULL
  OR REGEXP_LIKE(MESE_RIFERIMENTO, '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

--------------------------------------------------------------------------------
-- 5. Importi e quantita non negativi sui dettagli di consegna
--
-- Solo sul dettaglio consegne, dove il segno negativo non ha significato.
-- NON esteso agli importi di T_MOVIMENTAZIONE_VOCE_ST ne ai totali dell'header:
-- una voce di modifica/integrazione puo legittimamente essere una decurtazione,
-- e i totali della fonte vanno registrati come dichiarati (modello append-only,
-- il dato della fonte non si corregge in ingestione).
--------------------------------------------------------------------------------

ALTER TABLE T_MOVIMENTAZIONE_CONSEGNA_ST ADD CONSTRAINT CK_CONSEGNA_NON_NEGATIVI CHECK (
  NUMERO_CONSEGNE >= 0 AND TOTALE_PARZIALE_LORDO >= 0
);

--------------------------------------------------------------------------------
-- Verifica finale: tutti i vincoli aggiunti da questo script devono risultare
-- ENABLED / VALIDATED.
--------------------------------------------------------------------------------

SELECT CONSTRAINT_NAME, TABLE_NAME, STATUS, VALIDATED
  FROM USER_CONSTRAINTS
 WHERE CONSTRAINT_NAME IN ('CK_BATCH_COERENZA_OPERAZIONE', 'CK_BATCH_NON_AUTOREFERENTE',
                           'CK_BATCH_TIPO_ENTITA', 'CK_BATCH_ESITO', 'CK_BATCH_FORMATO_FILE',
                           'CK_BATCH_CONTATORI', 'CK_MOV_PERIODO', 'CK_ANAG_VALIDITA',
                           'CK_MOVVOCE_MESE_RIFERIMENTO', 'CK_CONSEGNA_NON_NEGATIVI')
 ORDER BY TABLE_NAME, CONSTRAINT_NAME;

--------------------------------------------------------------------------------
-- Se un ALTER fallisce con ORA-02293 (constraint violated) su ambiente popolato:
-- le righe non conformi vanno prima individuate, non aggirate. Esempio per il
-- periodo delle movimentazioni:
--
--   SELECT ID_MOVIMENTAZIONE_ST, ID_MOVIMENTAZIONE, PERIODO_DA, PERIODO_A
--     FROM T_MOVIMENTAZIONE_ST WHERE PERIODO_A < PERIODO_DA;
--
-- Solo se si decide di accettare lo storico non conforme e vincolare da qui in
-- avanti si usa ENABLE NOVALIDATE, che verifica le sole righe nuove:
--
--   ALTER TABLE T_MOVIMENTAZIONE_ST ADD CONSTRAINT CK_MOV_PERIODO
--     CHECK (PERIODO_A >= PERIODO_DA) ENABLE NOVALIDATE;
--
-- E la scelta coerente col modello append-only — le righe storiche restano la
-- fotografia di cio che la fonte aveva dichiarato — ma va presa
-- consapevolmente, non come scorciatoia per far passare lo script.
--------------------------------------------------------------------------------
