# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Cos'è questo progetto

API di ingestione dati Oracle per i pagamenti dei rider Deliveroo ("RiderPay"). Riceve periodicamente tre file da un sistema esterno (`anagrafica.json`, `voci.csv`, `movimentazioni.json`, di esempio in `docs/`) e li scrive su Oracle con un modello **append-only / storicizzato (SCD Type 2)**: non si fa mai `UPDATE`/`DELETE` fisico sui dati di business, ogni nuovo arrivo genera una nuova versione datata, così è sempre possibile ricostruire "cosa si sapeva e quando" anche dopo correzioni. Il documento di design completo (comprensivo del codice Java "di riferimento" da cui è stato estratto questo progetto) è in `docs/progettazione_ingestion_oracle.md`.

Nessun repository Git/SVN inizializzato in questa directory al momento.

## Comandi

```bash
mvn clean compile          # compila
mvn clean package          # compila e produce target/riders2eLH.jar
mvn spring-boot:run         # avvio locale (richiede DB_HOST/DB_PORT/DB_SERVICE/DB_USER/DB_PASSWORD o profilo -Dspring.profiles.active=local)
```

Non esiste `src/test/`: nessun test automatizzato presente nel progetto, nonostante `spring-boot-starter-test` sia dichiarato in `pom.xml`.

### Deploy (ambiente dev)

```bash
mvn clean package deploy -Pdev
```

Il profilo Maven `dev` (attivo di default) copia `target/riders2eLH.jar` via SCP (task Ant `<scp>` in `maven-antrun-plugin`, fase `deploy`) sull'host configurato nella proprietà `remote.deploy.host` del `pom.xml`, usando la chiave privata in `remote.deploy.keyfile` (deve essere in formato PEM classico `RSA PRIVATE KEY`, **non** il formato OpenSSH moderno — JSch 0.1.55 non lo supporta). Dopo il deploy, sul server:

```bash
sudo systemctl restart riders2eLH
sudo systemctl status riders2eLH
journalctl -u riders2eLH -n 50 --no-pager
```

`deploy/riders2eLH.service` è la unit systemd di riferimento (jar eseguito come processo standalone con Tomcat embedded, non un WAR su Tomcat esterno). **Il deploy Maven non la copia**: il task `<scp>` trasferisce solo `${finalName}.jar`, quindi ogni modifica alla unit va portata sul server a mano (`scp` in `/tmp`, poi `cp` in `/etc/systemd/system/` e `daemon-reload`) — vedi `deploy/README.md`. I segreti non stanno mai nel repo: vanno in un `EnvironmentFile` esterno sul server (`/opt/riders2eLH/riders2eLH.env`, permessi `600`), referenziato dalla unit — `DB_PASSWORD` e `KEYSTORE_PASSWORD`.

**HTTPS**: la porta 9443 serve TLS terminato da Tomcat embedded (`server.ssl.*` in `application-local.yml`); **davanti all'applicazione c'è però un reverse proxy su un altro host** — vedi la sezione "Topologia di rete" qui sotto. Il keystore PKCS12 sta sul server in `/opt/riders2eLH/riders2eLH-keystore.p12` (permessi `600`, proprietario = utenza del servizio), generato con `keytool -genkeypair -alias riderpay -storetype PKCS12 ... -ext "SAN=ip:10.10.7.46"` — il `SAN` è necessario perché i client validano quello, non il `CN`. `keytool` non è nel `PATH` sul server: va invocato per percorso assoluto dal JRE 21 usato dalla unit. Il certificato è **self-signed**, quindi i client devono disattivare la verifica (`curl -k`, Postman: SSL certificate verification off); per uscire da dev serve un certificato della CA aziendale. Nota che attivando `server.ssl` la 9443 non risponde più in HTTP.

`deploy/README.md` contiene i comandi di setup una tantum sul server (directory, `EnvironmentFile`, unit systemd, generazione del keystore TLS). L'host di deploy è definito in `remote.deploy.host` nel `pom.xml` — quella resta la fonte autorevole in caso di dubbio.

**Parametri d'ambiente dev**: DB Oracle su `10.10.7.187:1521/SVILUPPO.testsub.prod.oraclevcn.com`, utenza applicativa `CDL0036` (quella con la quota tablespace descritta più sotto). Sul server dev (`10.10.7.46`, hostname `microservices-with-gui`) l'utente SSH è `f.cavaliere`. La chiave privata di deploy (`remote.deploy.keyfile` nel `pom.xml`) è su questa postazione in `C:\Sirfin Documents\ProdKey\riderpay_deploy_key`.

**Rename `riderpay` → `riders2eLH` (13 agosto 2026).** Sono cambiati `artifactId`/`name`/`finalName` nel `pom.xml`, il nome del jar, la unit systemd, `/opt/riders2eLH/`, il prefisso di configurazione `riders2eLH.security.jwt.*`, il package Java (`it.panea.deliveroo.riders2elh`, minuscolo per convenzione) e la classe main (`Riders2eLHApplication`). Tre nomi restano volutamente al vecchio valore, perché non sono etichette ma riferimenti a cose che esistono già fuori dal repo:

| Cosa | Perché non è stato rinominato |
|---|---|
| `key-alias: riderpay` (`application-local.yml`) | è l'alias inciso nel keystore PKCS12 alla generazione; cambiarlo senza rigenerare il keystore impedisce l'avvio |
| `issuer: riderpay-auth-server` (`application.yml`) | finisce nel claim `iss` dei JWT emessi: è un valore di protocollo, da concordare con chi verifica i token |
| `riderpay_deploy_key` (`remote.deploy.keyfile`) | file di chiave privata fuori dal repo, la cui pubblica è già in `authorized_keys` sul server |
| `api.riderpay` (`SCOPE_CONCESSI`) | lo scope non è verificato da nessuna parte (`SecurityConfig` richiede solo `.authenticated()`): rinominarlo richiederebbe anche un `ALTER TABLE ... MODIFY` del `DEFAULT` senza alcun effetto funzionale |

**La directory del progetto resta `riderpay`** (punto 3 del rename, non affrontato): il path locale è ancora `C:\Svil\IntelliJ\Workspace\riderpay`, e non ha effetti sul build né sul deploy.

La migrazione sul server è stata **completata il 13 agosto 2026**: `/opt/riders2eLH/` con env file e keystore rinominati, unit `riders2eLH.service` attiva, vecchio servizio `riderpay` fermato e rimosso. Verificata con emissione di un token su `POST /oauth2/token`.

Anche il `client_id` di test è passato a `riders2elh-test` (13 agosto 2026), con un `UPDATE` su `T_CLIENT_OAUTH`; il secret e il suo hash bcrypt sono invariati, perché non dipendono dal `client_id`. **I batch storici in `T_BATCH_CARICAMENTO` conservano `CLIENT_ID = 'riderpay-test'`**: la colonna registra chi ha caricato quel batch nel momento in cui è avvenuto, e riscriverla contraddirebbe il modello append-only. Sono lo stesso client — vale la pena saperlo quando si interroga l'audit dei caricamenti fatti prima di quella data.

## Architettura

### Storicizzazione (pattern comune a tutte le tabelle `_ST`)

Ogni tabella storicizzata (`T_RIDER_ANAGRAFICA_ST`, `T_VOCE_ST`, `T_MOVIMENTAZIONE_ST`) segue lo stesso schema:
- `ID_BATCH_CARICAMENTO` — FK verso `T_BATCH_CARICAMENTO`, traccia quale invio ha portato quella riga.
- `DT_INSERIMENTO` — timestamp esatto di scrittura.
- `FLAG_ULTIMA_VERSIONE` (`S`/`N`) — unico campo "aggiornabile": quando arriva una versione più recente per la stessa chiave business, la vecchia riga passa a `N` e si inserisce una nuova riga con `S`.
- `STATO_RECORD` (`ATTIVO`/`ANNULLATO`) — un annullamento è anch'esso un insert (nuova riga con `STATO_RECORD='ANNULLATO'`), mai una cancellazione fisica.
- Viste `VW_*_CORRENTE` filtrano `FLAG_ULTIMA_VERSIONE='S' AND STATO_RECORD='ATTIVO'` per lo stato attuale, mentre le tabelle `_ST` restano l'unica fonte per l'audit storico completo.

Il flusso di scrittura (`sostituisciVersioneCorrente` in ogni repository, `@Transactional`) chiude sempre prima la versione corrente (`UPDATE ... SET FLAG_ULTIMA_VERSIONE='N'`) e poi inserisce la nuova, nella stessa transazione.

`T_RIDER_ANAGRAFICA_ST` e `T_MOVIMENTAZIONE_ST` sono partizionate `RANGE (DT_INSERIMENTO)` con `INTERVAL` mensile automatico; le tabelle di dettaglio (`T_MOVIMENTAZIONE_CONSEGNA_ST`, `T_MOVIMENTAZIONE_VOCE_ST`) sono `PARTITION BY REFERENCE` ed eredita il partizionamento dal padre. `T_VOCE_ST` non è partizionata (basso volume). Indici univoci su espressione (`UX_ANAG_CORRENTE`, `UX_VOCE_CORRENTE`, `UX_MOV_CORRENTE`, es. `CASE WHEN FLAG_ULTIMA_VERSIONE='S' THEN ID_RIDER END`) garantiscono al massimo una riga "corrente" per chiave business — il DDL completo è in `docs/db/00-ddl_riderpay.sql` (creazione da zero) più `docs/db/01-init-dml.sql` (client OAuth di test). `src/main/resources/db/ddl_riderpay.sql` è la copia allineata nel progetto e in più conserva la sezione `T_CLIENT_OAUTH`, che l'export in `docs/db/` ha spostato nel file DML: nessuno dei due viene eseguito dall'applicazione all'avvio (non c'è `spring.sql.init`), sono script da applicare a mano.

Le modifiche di schema su un ambiente già popolato stanno in `src/main/resources/db/migrazioni/` (un file per data, da applicare a mano nello stesso ordine). Una vista definita come `SELECT *` — tutte le `VW_*_CORRENTE` lo sono — **non** espone da sola le colonne aggiunte dopo la sua creazione: la lista viene fissata alla creazione, quindi ogni `ALTER TABLE ... ADD` va seguito dal `CREATE OR REPLACE VIEW` corrispondente, come fa la migrazione del 13 agosto 2026 (`TELEFONO_CELLULARE`, `EMAIL` su `T_RIDER_ANAGRAFICA_ST`).

**Attenzione a `SimpleJdbcInsert` e colonne con `DEFAULT` Oracle**: in questo ambiente, `SimpleJdbcInsert` non omette silenziosamente dallo statement le colonne assenti dalla `Map` passata se hanno un `DEFAULT` lato Oracle — tenta di inserire `NULL` esplicito, causando `ORA-01400`. Per questo `DT_INSERIMENTO` e `FLAG_ULTIMA_VERSIONE` vengono sempre impostate esplicitamente nel codice Java (mai lasciate al `DEFAULT` del DDL) in `RiderAnagraficaRepository`, `VoceRepository`, `MovimentazioneRepository`. Se si aggiungono nuove colonne con `DEFAULT` a tabelle inserite via `SimpleJdbcInsert`, vanno impostate esplicitamente nello stesso modo.

**`SimpleJdbcInsert` e colonne `GENERATED ALWAYS AS IDENTITY` (`ORA-32795`)**: ogni `SimpleJdbcInsert` su una tabella con colonna identity deve dichiararla con `usingGeneratedKeyColumns("<COLONNA>")` — **anche quando la chiave restituita non serve**. Senza quella dichiarazione la colonna identity finisce tra quelle dell'`INSERT` generato e Oracle rifiuta con `ORA-32795: cannot insert into a generated always identity column`, benché il codice non la passi mai nella `Map`. Era il caso di `insertConsegna`/`insertVoce` in `MovimentazioneRepository` (`T_MOVIMENTAZIONE_CONSEGNA_ST`, `T_MOVIMENTAZIONE_VOCE_ST`), gli unici due insert del progetto che scartano la chiave generata — corretto il 12 agosto 2026.

**Quota sul tablespace e partizionamento `INTERVAL` (`ORA-01950`)**: l'utenza applicativa deve avere quota sul tablespace, altrimenti gli insert sulle tabelle partizionate falliscono con `ORA-01950: no privileges on tablespace`. Il sintomo è ingannevole per due motivi. Primo: gli insert sulle tabelle *non* partizionate (es. `T_BATCH_CARICAMENTO`) continuano a funzionare perché scrivono in extent già allocati, mentre `T_RIDER_ANAGRAFICA_ST`/`T_MOVIMENTAZIONE_ST` devono materializzare la nuova partizione mensile `INTERVAL` — operazione che richiede di allocare un segmento, quindi quota. Sembra quindi un problema della singola tabella, non dell'utenza. Secondo: Spring traduce `ORA-01950` in `BadSqlGrammarException`, il cui `getMessage()` riporta solo `PreparedStatementCallback; bad SQL grammar []` — nessun riferimento a spazio o privilegi. Diagnosi e rimedio (da utenza amministrativa, non da quella applicativa):

```sql
-- come utenza applicativa: MAX_BYTES=-1 illimitata, nessuna riga = nessuna quota
SELECT TABLESPACE_NAME, BYTES, MAX_BYTES FROM USER_TS_QUOTAS;
-- rimedio, da utenza amministrativa
ALTER USER CDL0036 QUOTA UNLIMITED ON CDL0036_TSDAT;
-- verifica che la partizione del mese corrente sia stata materializzata
SELECT PARTITION_NAME, TABLESPACE_NAME, HIGH_VALUE FROM USER_TAB_PARTITIONS
 WHERE TABLE_NAME = 'T_RIDER_ANAGRAFICA_ST' ORDER BY PARTITION_POSITION;
```

Non serve riavviare l'applicazione dopo l'`ALTER USER`: al caricamento successivo Oracle crea da sé la partizione mancante (nome generato tipo `SYS_P3303`, `HIGH_VALUE` al primo giorno del mese seguente) e l'insert passa.

Il DDL non specifica `TABLESPACE` sulle tabelle partizionate, quindi le partizioni `INTERVAL` finiscono nel `DEFAULT_TABLESPACE` dell'utenza (`SELECT DEFAULT_TABLESPACE FROM USER_USERS`): la quota va concessa su *quello*. In dev coincide con `CDL0036_TSDAT`.

Occorso e risolto in dev il 12 agosto 2026 (`T_RIDER_ANAGRAFICA_ST`, partizione di agosto). **Nota diagnostica**: davanti a `ORA-01950` conviene prima riprovare la chiamata — se la quota è stata nel frattempo concessa il problema è già superato, e si evita di inseguire ipotesi più complicate (utenza connessa diversa dal proprietario dello schema, tablespace di default divergente) che in questo caso si sono rivelate tutte infondate.

**Errori per record: leggere sempre la causa radice.** I tre service di caricamento catturano le eccezioni per record e le registrano in `T_BATCH_CARICAMENTO_ERRORE` senza interrompere il batch. `e.getMessage()` da solo perde il codice `ORA-`: usare `DiagnosticaErrori.messaggioCompleto(e)` (in `common/`), che scende lungo la catena delle cause ed esplicita l'`ORA-` di ogni `SQLException`. I service loggano anche a `ERROR` con lo stack trace completo. **Limite noto**: il ciclo non distingue un errore del singolo record da uno infrastrutturale. Un `ORA-01950` o un DB irraggiungibile fanno fallire ogni record allo stesso modo, producendo N iterazioni inutili e N righe identiche in `T_BATCH_CARICAMENTO_ERRORE` (su `movimentazioni.json` sarebbero migliaia). Non è stato affrontato perché richiede di decidere quali errori interrompono il batch — scelta di comportamento, non correzione di un bug.

**Esecuzione asincrona dei caricamenti, con polling su GET /batch/{idBatch} (20 agosto 2026) — breaking change concordato col cliente.** Le tre POST di caricamento (`/anagrafiche`, `/movimentazioni`, `/voci`) non elaborano più la lista in modo sincrono: creano il batch, avviano l'elaborazione su un thread separato (bean `batchTaskExecutor` in `config/AsyncConfig.java`, `@EnableAsync`, pool deliberatamente piccolo — `corePoolSize=2`, `maxPoolSize=4` — per non esaurire il pool Hikari a 20 connessioni condiviso col traffico REST sincrono) e rispondono **subito `202`** con solo `idBatch` e `esito=IN_CORSO`. Il body torna a essere `BatchEsitoResponse`, ma con contatori a zero e non definitivi: il client deve fare polling su `GET /api/v1/batch/{idBatch}` per l'esito reale. Non è più `201`/`207`: qualunque client che legga i contatori direttamente dalla risposta della POST va aggiornato.

Ogni service ha ora due metodi al posto di `carica`: `avviaCaricamento` (sincrono, crea il batch, ritorna l'id) e `elaboraAsync` (`@Async("batchTaskExecutor")`, fa il ciclo per-record). **Il controller deve chiamare entrambi**, non il service internamente: una self-invocation da dentro lo stesso service bypasserebbe il proxy Spring che rende `@Async` effettivo, ed eseguirebbe tutto in modo sincrono senza errori visibili — un difetto silenzioso, non un'eccezione a runtime.

`common/EsitoBatch` ha due nuovi valori: `IN_CORSO` (scritto da `avviaElaborazione`, prima operazione del task asincrono, insieme a `DT_INIZIO_ELABORAZIONE` — che quindi da questa data è finalmente popolata) ed `ERRORE_TECNICO` (scritto solo se l'intero corpo di `elaboraAsync` fallisce per un'eccezione **fuori** dal ciclo per-record, es. `avviaElaborazione`/`chiudiBatch` che perdono la connessione). Il secondo caso non era nel piano originale: un metodo `@Async void` che lancia un'eccezione non catturata finisce solo nell'`AsyncUncaughtExceptionHandler` di default di Spring (loggato, nulla di più) — senza un `try/catch` esplicito attorno a tutto il corpo, il batch resterebbe bloccato in `IN_CORSO` per sempre, senza che il polling lo segnali in alcun modo. Il `catch` esterno tenta di chiudere il batch come `ERRORE_TECNICO`; se anche questo secondo tentativo di scrittura fallisce (stesso motivo del primo fallimento, tipicamente un DB irraggiungibile), resta solo il log — non c'è garanzia assoluta di uscire da `IN_CORSO`.

Per questo residuo, `GET /api/v1/batch/{idBatch}` (e `GET /api/v1/batch`) calcolano **in lettura, senza scrivere nulla sulla riga**, un campo `probabilmenteBloccato` in `BatchRow`: `true` se `esito=IN_CORSO` e sono passati più dei minuti configurati in `riders2eLH.batch.soglia-bloccato-minuti` (default 30) da `DT_INIZIO_ELABORAZIONE`. Calcolato in `BatchQueryService.conProbabileBlocco`, non nel `RowMapper` di `BatchCaricamentoRepository` (che non ha — né dovrebbe avere — nozione di una soglia di tempo, è puro mapping colonna→campo).

`ESITO` era `VARCHAR2(10)`: sufficiente per `IN_CORSO` (8 caratteri) ma non per `ERRORE_TECNICO` (14) — la migrazione `2026-08-20_batch_esito_in_corso.sql` allarga la colonna a `VARCHAR2(20)` **prima** di ricreare il `CHECK` esistente (`CK_BATCH_ESITO`, che va sempre `DROP`+`ADD`, Oracle non supporta `ALTER CONSTRAINT` sulla condizione). Se in futuro si aggiungono altri valori a `EsitoBatch`, verificarne sempre la lunghezza contro la colonna prima di aggiungerli al `CHECK`, altrimenti il primo batch che raggiunge quello stato fallisce con `ORA-12899` a runtime, non a compile time.

**Righe anomale consultabili via API (20 agosto 2026).** `T_BATCH_CARICAMENTO_ERRORE` ha una nuova colonna `NUMERO_RIGA` (migrazione `2026-08-20_batch_errore_numero_riga.sql`, nullable, posizione 0-based del record nel payload originale): serve perché `CHIAVE_BUSINESS` da sola non basta quando è essa stessa mancante o malformata (es. `id_rider` assente dal record). `BatchCaricamentoRepository.registraErrore` ha quindi cambiato firma (nuovo parametro `Integer numeroRiga`, secondo argomento) — chiamato dai tre service con l'indice del ciclo `for`, ora indicizzato (`for (int i = 0; i < lista.size(); i++)`) al posto del `for-each` precedente. `PAYLOAD_JSON` non riceve più `dto.toString()` (rappresentazione Java, non JSON) ma `objectMapper.writeValueAsString(dto)` (nuova dipendenza `ObjectMapper` iniettata nei tre service), con fallback silenzioso a `toString()` solo se la serializzazione stessa fallisse. Nuovo endpoint `GET /api/v1/batch/{idBatch}/errori?page=&size=` (`BatchController`/`BatchQueryService`/`BatchCaricamentoRepository.elencaErrori`) espone queste righe: 404 se il batch non esiste, 400 se `page`/`size` non validi, altrimenti 200 paginato (`OFFSET ... FETCH NEXT ... ROWS ONLY`, default `size=100`) — necessario perché un batch da 30000 record può generare fino a 30000 righe di errore.

**Validazione di formato/coerenza: per record dentro il service, non `@Valid` sulla lista (20 agosto 2026).** `common/ValidatoreFormato.java` valida email, telefono, CAP, codice fiscale (con verifica del carattere di controllo) e coerenza `periodo_da`/`periodo_a` — invocato dentro `caricaSingolo`/`caricaSingola` dei tre service, **prima** di `sostituisciVersioneCorrente`, non con `@Valid` sul `List<...>` nel controller: quest'ultimo respingerebbe l'intero batch con 400 al primo valore anomalo, invece di isolare il singolo record KO in `T_BATCH_CARICAMENTO_ERRORE` (motivazione già presente nel commento a `RiderAnagraficaDto`). Le violazioni sono raccolte in `RecordNonValidoException` (in `common/`) e finiscono nel `catch (Exception e)` già esistente nel ciclo, senza modificarne la struttura.

**Attenzione allo schema del codice fiscale italiano**: il mese di nascita è codificato con **una sola lettera** (posizioni 9, es. `RSSMRA80A01F205X` → `A` = gennaio), non due. Una regex scritta con `[A-EHLMPR-T]{2}` invece di `[A-EHLMPR-T]` sposta la lettura di tutti i gruppi successivi (giorno, codice catastale, carattere di controllo) e respinge come non validi anche i codici fiscali corretti — bug reale occorso durante l'implementazione, rilevato perché i 4 campioni di `docs/anagrafica.json` (tutti CF validi) risultavano tutti respinti. Il calcolo del carattere di controllo (16° carattere, tabelle valori pari/dispari + resto modulo 26) è invece indipendente e corretto di per sé; da riverificare insieme se in futuro l'algoritmo viene toccato.

### Struttura dei package

```
it.panea.deliveroo.riders2elh/
├─ Riders2eLHApplication.java
├─ common/            → enum di dominio, eccezioni custom, ChecksumUtils, SecurityUtils, DiagnosticaErrori
├─ dto/                → record REST (payload 1:1 con anagrafica.json/voci.csv/movimentazioni.json)
├─ repository/         → accesso Oracle via JdbcTemplate/SimpleJdbcInsert (no JPA/Hibernate)
├─ service/            → logica di caricamento/annullamento/rettifica
├─ api/                → controller REST + TokenController (OAuth2) + GlobalExceptionHandler
└─ config/security/    → SecurityFilterChain, generazione chiavi RSA, firma JWT
```

Nessun ORM: tutto l'accesso dati passa da `JdbcTemplate`/`SimpleJdbcInsert` con `RowMapper` espliciti — i `record` in `repository/*Row.java` sono le proiezioni di riga, distinti dai DTO REST in `dto/`.

### Endpoint REST

Quattro controller sotto `/api/v1`, tutti seguono lo stesso schema (POST ingestione → 201/207, GET stato corrente/storico, DELETE annullamento logico con body `MotivoRequest`, POST `.../rettifica`):
- `AnagraficaController` — `/anagrafiche`, `/rider/{idRider}/anagrafica[/rettifica]`
- `VoceController` — `/voci` (upload multipart CSV), `/voci/{idVoce}[/storico|/rettifica]`
- `MovimentazioneController` — `/movimentazioni`, `/rider/{idRider}/movimentazioni`, `/movimentazioni/{idMovimentazione}[/storico|/rettifica]`
- `BatchController` — `/batch/{idBatch}`, `/batch?tipoOperazione=...&dataInizio=...&dataFine=...` (audit annullamenti/rettifiche)

**Ricerca batch per intervallo di date (13 agosto 2026).** `GET /api/v1/batch` accetta `dataInizio`/`dataFine` (`yyyy-MM-dd`, senza orario) oltre a `tipoOperazione`; i filtri sono opzionali e combinabili. Tre scelte da conoscere prima di modificarlo:

1. **Query param, non path variable.** Una rotta `/batch/{dtRiferimento}` colliderebbe con `/batch/{idBatch}` (stesso pattern a un segmento → `IllegalStateException: Ambiguous mapping` all'avvio, l'app non parte). Un path alternativo tipo `/batchByData/...` eviterebbe la collisione ma sta fuori dal `@RequestMapping("/api/v1/batch")` di classe, e moltiplica gli endpoint per ogni combinazione di filtri: come query param, "annullamenti del 13 agosto" è `?tipoOperazione=ANNULLAMENTO&dataInizio=2026-08-13&dataFine=2026-08-13` senza codice nuovo.
2. **Intervallo semiaperto, non `TRUNC`.** Il filtro è `DT_RICEZIONE >= dataInizio 00:00 AND DT_RICEZIONE < dataFine+1giorno 00:00`, equivalente a `TRUNC(DT_RICEZIONE) BETWEEN TRUNC(?) AND TRUNC(?)` — il `<` stretto sull'estremo superiore più il giorno aggiunto includono tutto `dataFine` fino a `23:59:59.999...`, quindi **entrambi gli estremi sono inclusi**. Scritto così perché `TRUNC` sulla colonna non è sargable e impedirebbe l'uso di un indice su `DT_RICEZIONE` (oggi assente, ma un range scan è il caso in cui servirebbe). Il chiamante non specifica mai l'orario in nessuna delle due forme: gli estremi sono derivati in Java dal `LocalDate`.
3. **Nessuna conversione di fuso.** `Timestamp.valueOf(LocalDateTime)` non converte, e `DT_RICEZIONE` è un `TIMESTAMP` Oracle senza fuso: il giorno di ricerca è **il giorno come sta scritto in colonna**, considerata il valore autoritativo. Conseguenza da tenere presente: `creaBatch` scrive `Timestamp.from(Instant.now())`, quindi se la JVM del server non è in `Europe/Rome` (il journal logga in `Z`) un batch ricevuto tra mezzanotte e le 02:00 italiane è archiviato nel giorno precedente e va cercato in quel giorno. Alternativa scartata: calcolare gli estremi in `Europe/Rome`.

**`tipoOperazione` è case-insensitive** (13 agosto 2026): il `@RequestParam` è dichiarato `String` e non `TipoOperazione` di proposito, perché con l'enum la conversione avviene dentro Spring *prima* che il codice applicativo veda il valore — impossibile normalizzarlo, e un valore errato diventa un `500` opaco. `BatchQueryService.convertiTipoOperazione` fa `trim()` + `toUpperCase(Locale.ROOT)` e su valore non riconosciuto risponde `400` elencando quelli ammessi. `Locale.ROOT` e non il locale di default né `Locale.ITALIAN`: i valori dell'enum sono identificatori ASCII, non testo in lingua, e con il locale di default una JVM avviata con `-Duser.language=tr` maiuscolizzerebbe `i` in `İ`, rompendo `rettifica`→`RETTIFICA`. Se in futuro si aggiungono altri filtri enum (`tipoEntita`, `esito`), conviene sostituire questo metodo con un `Converter<String, Enum>` registrato nel contesto, che vale per tutti i `@RequestParam` senza ripetizioni.

`BatchQueryService.elenca` valida le combinazioni incoerenti con `RichiestaNonValidaException`→400 (nuova eccezione in `common/`, per parametri validi singolarmente ma non insieme — quindi non intercettabili con le annotazioni di validazione): una sola delle due date, oppure `dataInizio > dataFine`. Nessuna corrispondenza è `200` con lista vuota, non 404.

`GlobalExceptionHandler` centralizza il mapping eccezioni → HTTP: `RisorsaNonTrovataException`→404, `ConflittoConcorrenzaException`→409, `ClientNonAutorizzatoException`→401, `MethodArgumentNotValidException`→400.

### Topologia di rete: c'è un reverse proxy, su un altro host (13 agosto 2026)

Scoperto in dev il 13 agosto 2026, mentre si provava il nuovo URL: **`devws.paneagroup.it` non è la macchina dell'applicazione.**

| Nome | IP | Cos'è |
|---|---|---|
| `10.10.7.46` | privato | la macchina dell'applicazione (`microservices-with-gui`), dove gira la unit `riders2eLH` |
| `devws.paneagroup.it` | `150.230.147.192` (pubblico) | un reverse proxy su un **host diverso**, che inoltra verso il backend |

Conseguenze pratiche:

- **Per provare l'API si usa `https://10.10.7.46:9443/riders2elh/`** (`baseUrl` della collection Postman). Verificato il 13 agosto 2026: `POST /riders2elh/oauth2/token` risponde `200` con token valido. Il certificato copre anche l'IP (SAN con `IPAddress`), quindi non serve altro oltre a disattivare la verifica del self-signed.
- **`https://devws.paneagroup.it:9443/riders2elh/` restituisce `502 Bad Gateway`**: è il proxy che non riesce a raggiungere il backend. Una pagina 502 in HTML non la produce mai Tomcat embedded — se la si vede, si sta parlando col proxy, non con l'applicazione. La configurazione del proxy **non è nel repo né su questa macchina** (nessun nginx installato su `10.10.7.46`): è in mano ai sistemisti. Da verificare con loro, in ordine di probabilità: (1) il proxy parla HTTP verso il backend, che accetta **solo** HTTPS sulla 9443; (2) inoltra mantenendo il prefisso `/riders2elh` oppure lo strippa — se lo strippa va **rimosso** `server.servlet.context-path` da `application.yml`, perché l'app deve stare su `/`; (3) accetta il certificato self-signed del backend (`proxy_ssl_verify off` o certificato nel truststore).
- Chiamando via `devws.paneagroup.it`, **il certificato che il client valida è quello del proxy**, non quello dell'applicazione: il SAN rigenerato serve al proxy per validare il backend, non al browser.

**Diagnosi**: davanti a un errore su `devws.paneagroup.it`, il primo passo è ripetere la chiamata su `10.10.7.46` (o su `localhost` dal server) per separare "app rotta" da "proxy mal configurato". Dal server:

```bash
curl -vk -X POST 'https://localhost:9443/riders2elh/oauth2/token' -d 'grant_type=client_credentials&client_id=riders2elh-test&client_secret=...'
echo | openssl s_client -connect localhost:9443 2>/dev/null | openssl x509 -noout -subject -ext subjectAltName
```

**Falso allarme da conoscere**: all'avvio Tomcat logga `certificate type [UNDEFINED] configured from keystore [/home/<utente>/.keystore] using alias [riderpay]`. Quel path è il **default interno** di `SSLHostConfigCertificate`, non il file effettivamente aperto — Spring Boot passa il keystore già risolto. Il file `~/.keystore` non esiste nemmeno. Per sapere quale certificato è davvero in uso si interroga la porta con `openssl s_client` (comando sopra), non si legge quella riga di log.

### Rigenerazione del keystore TLS con SAN multiplo (13 agosto 2026)

Il keystore è stato rigenerato per includere il nome DNS oltre all'IP: i client validano il SAN, quindi con `SAN=ip:10.10.7.46` da solo una chiamata a `https://devws.paneagroup.it:9443` dà *hostname mismatch* (errore distinto, e più bloccante, del solito avviso self-signed). SAN attuale: `DNS:devws.paneagroup.it, IP Address:10.10.7.46`, `CN=devws.paneagroup.it`, validità 10 anni.

Procedura seguita, da ripetere se il SAN cambia (i primi due passi servono perché `-genkeypair` su un keystore esistente **aggiunge** una voce e fallisce se l'alias c'è già; si sposta il vecchio file invece di cancellarlo, così il rollback è immediato):

```bash
grep -E "ExecStart|User=" /etc/systemd/system/riders2eLH.service   # utenza e percorso del JRE
sudo mv /opt/riders2eLH/riders2eLH-keystore.p12 /opt/riders2eLH/riders2eLH-keystore.p12.bak
sudo /usr/lib/jvm/jre-21-openjdk-21.0.11.0.10-1.0.1.el8.x86_64/bin/keytool -genkeypair \
  -alias riderpay -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore /opt/riders2eLH/riders2eLH-keystore.p12 -validity 3650 \
  -dname "CN=devws.paneagroup.it, OU=IT, O=Panea Group, C=IT" \
  -ext "SAN=dns:devws.paneagroup.it,ip:10.10.7.46"
sudo chown f.cavaliere:sistemisti /opt/riders2eLH/riders2eLH-keystore.p12
sudo chmod 600 /opt/riders2eLH/riders2eLH-keystore.p12
```

Tre punti dove è facile sbagliare: la password va inserita **identica a `KEYSTORE_PASSWORD`** nell'`EnvironmentFile` (in PKCS12 coincide con quella della chiave; se differisce l'avvio fallisce); l'alias deve restare `riderpay`, che è quello cercato da `application-local.yml`; il `chown` è obbligatorio perché `keytool` sotto `sudo` crea il file come `root` e il servizio non lo leggerebbe (utenza dalla `User=` della unit, gruppo `sistemisti`). Il certificato resta **self-signed**: l'hostname mismatch sparisce, l'avviso di CA non attendibile no — per quello serve un certificato della CA aziendale.

### Context path `/riders2elh` (13 agosto 2026)

`server.servlet.context-path: /riders2elh` in `application.yml`: **prefissa tutti i path**, non solo Swagger. `/api/v1/batch` → `/riders2elh/api/v1/batch`, `/oauth2/token` → `/riders2elh/oauth2/token`. Va aggiornato il `baseUrl` della collection Postman e qualunque altro client. Minuscolo per coerenza col package Java e con l'URL concordato; il nome del jar, la unit systemd e `/opt/riders2eLH/` restano `riders2eLH`.

Due punti che il context path **non** risolve, entrambi fuori dal repo:

- **`devws.paneagroup.it`** dipende dal DNS aziendale, non dall'applicazione: il servizio ascolta su tutte le interfacce e risponde a qualunque nome che risolva su `10.10.7.46`.
- **Il certificato TLS non copre quel nome.** Il keystore è generato con `SAN=ip:10.10.7.46` e i client validano il SAN: chiamando `https://devws.paneagroup.it:9443` si ottiene un errore di *hostname mismatch*, distinto dal solito avviso self-signed. Serve rigenerare il keystore con `-ext "SAN=dns:devws.paneagroup.it,ip:10.10.7.46"`, mantenendo `-alias riderpay`.

Note tecniche: i `requestMatchers` di `SecurityConfig` sono relativi al context path (Spring Security lo rimuove prima del match), quindi `/api/v1/**` e `/swagger-ui/**` continuano a valere invariati. `OpenApiConfig` invece compone il `tokenUrl` dello schema oauth2 come `contextPath + "/oauth2/token"` leggendo `${server.servlet.context-path:}`: un path assoluto punterebbe alla root del server e il pulsante "Authorize" darebbe 404. Se il context path cambia, il `tokenUrl` segue da sé.

### Documentazione OpenAPI / Swagger UI (13 agosto 2026)

`springdoc-openapi-starter-webmvc-ui` 2.6.0 (SpringFox non supporta Boot 3 e non è manutenuto). Due path:

| Path | Cosa |
|---|---|
| `https://devws.paneagroup.it:9443/riders2elh/swagger-ui.html` | UI interattiva |
| `https://devws.paneagroup.it:9443/riders2elh/v3/api-docs` | spec OpenAPI 3 in JSON |

**⚠️ I due path sono `permitAll()` in `SecurityConfig`, quindi la descrizione completa dell'API — endpoint, schemi dei payload, nomi delle colonne — è leggibile senza autenticazione da chiunque raggiunga il servizio.** È una scelta consapevole per l'ambiente di dev (rete interna, TLS già self-signed), non un'omissione: la UI deve caricarsi *prima* che il client abbia un token, quindi non può stare sotto `.authenticated()`. **Da richiudere prima della produzione**, scegliendo tra: rimuovere il `permitAll()` e scaricare solo `/v3/api-docs` col token (nessuna UI dal browser); oppure condizionare il `permitAll()` al profilo attivo, ricordando che il profilo Maven `dev` è quello di default. Senza il `permitAll()` i due path cadono nell'`anyRequest().denyAll()` e la UI risponde `403`.

`OpenApiConfig` (in `config/`) dichiara due schemi di sicurezza alternativi, entrambi disponibili dal pulsante "Authorize": **oauth2** con flusso `client_credentials` (la UI chiama da sé `POST /oauth2/token`) e **bearerAuth** per incollare a mano un token ottenuto altrove — il ripiego se il browser blocca la chiamata al token endpoint per il certificato self-signed non accettato. Senza uno schema dichiarato, ogni "Try it out" su `/api/v1/**` tornerebbe `401`. `TokenController.emettiToken` ha `@SecurityRequirements` (vuoto) perché è pubblico: senza, la UI vi allegherebbe il Bearer facendo credere che serva già un token per ottenerne uno.

**springdoc non legge i javadoc**: la documentazione visibile viene solo dalle annotazioni `@Tag`/`@Operation`/`@ApiResponse`/`@Parameter`. Aggiungendo un endpoint va annotato a mano, altrimenti compare nella UI senza descrizione. Alla data sono annotati tutti i 19 endpoint dei 5 controller. `dto/ErroreResponse` esiste solo per lo schema degli errori: `GlobalExceptionHandler` costruisce una `Map` a runtime, e senza un tipo dichiarato la UI mostrerebbe un oggetto vuoto — **non è usata dal codice**, quindi se si cambiano le chiavi in `errore(...)` va aggiornata a mano (nulla lo impone al compilatore).

### Autenticazione OAuth2 (client_credentials)

L'app stessa fa da authorization server minimale (nessuna dipendenza da Spring Authorization Server o IdP esterno — implementazione manuale scelta perché serve solo il grant `client_credentials` machine-to-machine, senza consent screen/PKCE/refresh token):

1. I client autorizzati sono precaricati in `T_CLIENT_OAUTH` (INSERT amministrativo, `CLIENT_SECRET_HASH` in bcrypt — nessun endpoint self-service di registrazione).
2. `POST /oauth2/token` (`TokenController`) accetta `grant_type=client_credentials` con credenziali via Basic Auth o body form-urlencoded (entrambe supportate per compatibilità con Postman).
3. `ClientAuthenticationService` verifica client attivo + secret (bcrypt) via `ClientOAuthRepository`.
4. `JwtTokenService` firma un JWT RS256 (claim `sub`=client_id, `scope`, `exp` da `TOKEN_TTL_SECONDI` del client) usando la chiave privata di `RsaKeyProvider`.
5. **`RsaKeyProvider` genera la coppia di chiavi RSA in memoria a ogni avvio dell'applicazione, senza persistenza** — ogni riavvio del servizio invalida tutti i token già emessi; va rifatta la richiesta di token dopo ogni deploy/restart.
6. `SecurityConfig` protegge `/api/v1/**` come resource server OAuth2 (stateless, JWT verificato con la chiave pubblica locale — nessun `issuer-uri`/JWK endpoint HTTP, dato che firma e verifica avvengono nello stesso processo); `/oauth2/token` è pubblico, ogni altro path è `denyAll()`.
7. Nei controller, `SecurityUtils.clientIdAutenticato()` legge il claim `sub` dal `SecurityContextHolder` per popolare `CLIENT_ID` in `T_BATCH_CARICAMENTO` — non esiste più un header libero non autenticato per questo scopo.

La collection Postman (`docs/riderpay.postman_collection.json`) ha una cartella "00 - Autenticazione" che richiede il token e lo salva in una variabile di collezione, riusata come Bearer token a livello di collezione per tutte le altre richieste.

**Dopo ogni deploy/restart va ririchiesto il token.** `RsaKeyProvider` rigenera la coppia RSA a ogni avvio (punto 5 sopra), quindi ogni token emesso prima del riavvio diventa non verificabile. In Postman significa rieseguire "00 - Autenticazione" prima di qualsiasi altra richiesta: senza, tutte le chiamate falliscono con `401` e — se il client rimanda una richiesta malformata — con il `403` fuorviante descritto qui sotto. È la prima cosa da controllare quando "tutto smette di funzionare" subito dopo un rilascio.

**Anche "00 - Autenticazione" stessa può dare `401 invalid_token`/"malformed" (20 agosto 2026).** La richiesta `POST /oauth2/token` nella collection eredita per default il Bearer a livello di collezione (`{{accessToken}}`); se quella variabile è ancora vuota (primo run dopo l'import, o dopo un riavvio del server che ha invalidato l'ultimo token), Postman invia comunque l'header `Authorization: Bearer ` — vuoto ma presente — e `SecurityConfig` lo rifiuta come malformato **prima** che la richiesta arrivi a `TokenController` (pubblico via `@SecurityRequirements` vuoto, ma quell'annotazione non ha effetto se l'header è già un Bearer sintatticamente presente ma vuoto: Spring Security lo intercetta a monte del controller). Non è un problema di firma/scadenza del JWT — il messaggio "malformed" e non "invalid signature"/"expired" è già l'indizio. Fix nella collection: la richiesta ha ora `"auth": {"type": "noauth"}` esplicito, così non eredita più il Bearer di collezione. Se si ricrea questa richiesta da zero, va impostata `Authorization → No Auth` a mano.

**`403 insufficient_scope` non è (mai) un problema di scope.** `SecurityConfig` richiede solo `.authenticated()` su `/api/v1/**` e non esiste alcun controllo di scope nel progetto: l'header `WWW-Authenticate: Bearer error="insufficient_scope"` è solo la risposta standard del `BearerTokenAccessDeniedHandler` a un accesso negato, e il riferimento agli scope è puramente formale. Corrispondenza verificata in dev il 12 agosto 2026:

| Richiesta | Risposta |
|---|---|
| nessun header `Authorization` | `401` `Bearer` |
| Bearer vuoto o `{{accessToken}}` non risolto | `401` `invalid_token` |
| **token valido + `Content-Type` non accettato dall'endpoint** | **`403` `insufficient_scope`** |
| token valido + parametro non convertibile (data malformata, id non numerico) | `400` (dal 13 agosto 2026; **prima era `500`**) |
| token valido + eccezione non gestita nel codice applicativo | `500` (dal 13 agosto 2026; **prima era `403` `insufficient_scope`**) |

Quindi: **401 = problema di token, 403 = problema della richiesta** (tipicamente `Content-Type`), **500 = bug lato server, la causa è nel journal**, da diagnosticare nel journal e non dall'header. Il caso tipico è `POST /api/v1/voci`, che vuole `multipart/form-data` (`@RequestParam("file")`) e rifiuta JSON. In Postman va usato Body → form-data con la riga `file` di tipo File e il file **riselezionato a mano** dopo l'import (l'export della collection non incorpora i binari, e un form-data vuoto viene inviato senza `Content-Type` multipart); nessun header `Content-Type` manuale, che sovrascriverebbe il boundary generato. Verifica rapida da terminale — in PowerShell serve `curl.exe`, perché `curl` è alias di `Invoke-WebRequest` e non supporta `-H`/`-F`:

```bash
curl.exe -vk -X POST 'https://10.10.7.46:9443/api/v1/voci' -H "Authorization: Bearer $TOKEN" -F 'file=@docs/voci.csv'
```

`GlobalExceptionHandler` gestisce ora `HttpMediaTypeNotSupportedException`→415, `HttpRequestMethodNotSupportedException`→405, `HttpMessageNotReadableException`→400, `MissingServletRequestPartException`→400 e `MaxUploadSizeExceededException`→413 (20 agosto 2026, file oltre `spring.servlet.multipart.max-file-size` su `POST /voci` — prima cadeva nella rete `Exception`→500), così questi errori non arrivano più al client travestiti da 403 o 500.

**Parametri di richiesta non convertibili → 400 (13 agosto 2026).** `MethodArgumentTypeMismatchException` (la forma in cui Spring incarta la `ConversionFailedException` per `@RequestParam`/`@PathVariable`) è ora gestita: risponde `400` nominando il parametro e, per i tipi con un formato atteso, indicandolo — `yyyy-MM-dd` per le date, "valore numerico" per i numeri, l'elenco delle costanti per gli enum. Serve perché la `ConversionFailedException` da sola dice solo che il parse è fallito, lasciando indovinare **quale** dei parametri della query string sia il colpevole. Occorso in dev il 13 agosto 2026 passando un ISO datetime completo (`2026-08-13T14:39:17.499774464Z`) a `dataInizio`, che vuole una `LocalDate`: senza l'handler l'eccezione cadeva nella rete `Exception` e rispondeva `500`, cioè un bug del server per un errore del chiamante.

**Rete di sicurezza su `Exception` (13 agosto 2026).** `GlobalExceptionHandler` ha ora anche un `@ExceptionHandler(Exception.class)` che logga a `ERROR` con stack trace e risponde `500` con messaggio generico (nessun dettaglio interno al client). Gli handler specifici mantengono la precedenza, perché Spring risolve per tipo più specifico. Serve perché un'eccezione applicativa non gestita risaliva la filter chain fino all'`ExceptionTranslationFilter`, che la traduceva in `403 insufficient_scope`: un bug interno si presentava come problema di autorizzazione, mandando a cercare la causa nel token o negli scope invece che nel codice. **Non rimuovere questo handler** né lasciare che un `@ExceptionHandler` più specifico risponda 403.

**`ClassCastException: BigDecimal cannot be cast to Integer` sui `RowMapper` (13 agosto 2026).** Il driver JDBC Oracle mappa `NUMBER` su `java.math.BigDecimal`, quindi `(Integer) rs.getObject("COLONNA")` in un `RowMapper` solleva `ClassCastException`. **Il difetto è latente e si manifesta solo su alcune righe**: il cast di `null` passa senza errore, quindi la lettura funziona finché la colonna è `NULL` e rompe appena è valorizzata. Era il caso del `MAPPER` di `BatchCaricamentoRepository` su `ID_BATCH_RIFERIMENTO` (valorizzato solo su annullamenti/rettifiche) e `NUM_RECORD_TOTALI`/`_OK`/`_KO` (scritti da `chiudiBatch`): `GET /api/v1/batch/{idBatch}` funzionava sui batch ancora aperti e falliva su quelli chiusi, e `GET /api/v1/batch` si rompeva appena l'elenco ne conteneva uno. La forma corretta è `rs.getObject("COLONNA", Integer.class)` (JDBC 4.1: delega la conversione al driver invece di castare, e preserva il `null`) — **non** `getInt`/`getLong`, che restituiscono `0` sui `NULL` falsando i contatori. Alla data della correzione il pattern non è presente in nessun altro repository: da riverificare con `grep -rn "(Integer) rs.getObject\|(Long) rs.getObject" src/main/java/` se ricompare un sintomo simile.

### Revisione dello schema DB (18 agosto 2026)

Analisi della struttura in `docs/db/` (FK, indici, vincoli, ridondanze). L'impianto di fondo è risultato solido — SCD Type 2 coerente su tutte le `_ST`, indici univoci su espressione per la concorrenza, partizionamento `INTERVAL` + `BY REFERENCE` corretto. Sono emerse tre lacune, tutte tradotte in script applicabili, più alcune considerazioni **rinviate** (in fondo).

Gli script seguono la numerazione e la convenzione `NN-<tipo>-<argomento>.sql` di `docs/db/`:

| Script | Contenuto |
|---|---|
| `03-ddl-fk-master.sql` | `FK_ANAG_RIDER`, `FK_VOCE_DIZIONARIO` + query di controllo orfani da eseguire **prima** |
| `04-ddl-indici-fk.sql` | 6 indici sulle colonne FK + `DT_RICEZIONE`, con `GATHER_TABLE_STATS` |
| `05-ddl-check-coerenza.sql` | 10 `CHECK` di coerenza e dominio |

**Tutti e tre sono stati eseguiti sul DB di sviluppo** (`03` → `04` → `05`, con `03` in due tempi come previsto): gli script in `docs/db/` sono allineati allo schema Oracle dev, non solo validati staticamente.

**Le FK verso le master erano solo 2 su 4.** `T_RIDER` e `T_VOCE` sono descritte nel DDL come «target reale delle FK», ma solo `T_MOVIMENTAZIONE_ST.ID_RIDER` (`FK_MOV_RIDER`) e `T_MOVIMENTAZIONE_VOCE_ST.ID_VOCE` erano vincolate; `T_RIDER_ANAGRAFICA_ST.ID_RIDER` e `T_VOCE_ST.ID_VOCE` no. Le master erano quindi popolate per sola convenzione applicativa (`MasterKeyRepository.assicuraRider`/`assicuraVoce`, chiamati dai service prima di ogni insert storicizzato): un caricamento fuori dall'applicazione poteva creare un'anagrafica per un `ID_RIDER` assente da `T_RIDER`. Su ambiente popolato l'`ALTER` fallisce con `ORA-02298` se esistono orfani — vanno aggiunti alla master, **mai** cancellate righe storiche per far passare il vincolo. Sono FK ordinarie su tabella partizionata, **non** un `PARTITION BY REFERENCE`: non toccano il partizionamento esistente.

**Nessuna FK verso `T_BATCH_CARICAMENTO` aveva un indice sul lato figlio.** Conta soprattutto per le prestazioni: tutti i repository interrogano per batch (`WHERE ID_BATCH_CARICAMENTO = ? AND FLAG_ULTIMA_VERSIONE = 'S'`), quindi senza indice ogni chiamata è un full scan di una tabella che per costruzione cresce in modo monotono — il costo peggiora a ogni caricamento e non si stabilizza. Secondariamente, in Oracle una FK non indicizzata fa acquisire un **lock di tabella sul figlio** durante `DELETE`/`UPDATE` della chiave sul padre: teorico qui (nessun delete fisico), reale al primo intervento manutentivo sul padre. Gli indici sono `LOCAL` sulle partizionate (coerente con gli altri indici non univoci) e hanno `FLAG_ULTIMA_VERSIONE` in seconda posizione, così coprono da soli il predicato applicativo. `IX_BATCH_DT_RICEZIONE` è `(DT_RICEZIONE, TIPO_OPERAZIONE)` e non l'inverso: serve sia la ricerca per solo intervallo che quella per intervallo + tipo, l'ordine opposto non servirebbe la prima.

**I `CHECK` mancanti erano un'asimmetria, non una dimenticanza isolata.** `TIPO_OPERAZIONE`, `STATO_RECORD`, `FLAG_ULTIMA_VERSIONE` e `TIPO_SEZIONE` avevano tutti un vincolo di dominio; `TIPO_ENTITA`, `ESITO` e `FORMATO_FILE` — documentati nello stesso stile, con il commento `-- OK | KO | PARZIALE` accanto alla colonna — no. Aggiunti anche: coerenza `TIPO_OPERAZIONE` ↔ `ID_BATCH_RIFERIMENTO`/`MOTIVO_OPERAZIONE`, non-autoreferenza del batch, quadratura dei contatori, formato di `MESE_RIFERIMENTO` (con validazione del mese 01-12, non della sola lunghezza), non-negatività sul dettaglio consegne. Prima di scriverli è stato verificato che il codice attuale produca solo valori conformi: ogni percorso `RETTIFICA`/`ANNULLAMENTO` passa sia `idBatchRiferimento` sia `motivo`, e `motivo` è `@NotBlank` in `MotivoRequest`. I rami `IS NULL` su `ESITO` e `FORMATO_FILE` sono deliberati (il primo è `NULL` sui batch aperti, il secondo su rettifiche e annullamenti).

I due `CHECK` sulle date (`CK_MOV_PERIODO`, `CK_ANAG_VALIDITA`) sono gli unici che aggiungono una regola **davvero nuova**: i DTO hanno `@NotNull` sui campi singoli ma nessuna validazione incrociata, quindi un periodo invertito era accettato e storicizzato senza obiezioni. Deliberatamente **non** sono stati messi vincoli di non-negatività sugli importi delle voci né sui totali dell'header: una modifica/integrazione può essere legittimamente una decurtazione, e i totali della fonte vanno registrati come dichiarati.

**`docs/db/00-ddl_riderpay.sql` non era eseguibile.** Usava `NOT NULL DEFAULT <valore>` in 12 punti: Oracle richiede `DEFAULT` **prima** di `NOT NULL` e rifiuta l'ordine inverso con `ORA-00907`, quindi lo script si fermava al secondo `CREATE TABLE`. Corretto il 18 agosto 2026 (una occorrenza era già stata sistemata a mano, le altre 11 no). Da tenere presente per il futuro: `docs/db/` è un **export** e la copia autorevole in caso di divergenza è stata storicamente `src/main/resources/db/ddl_riderpay.sql`, che aveva l'ordine corretto — il contrario di quanto suggerirebbe il nome "creazione da zero". L'altro difetto dello stesso export (il DML che inseriva in `T_CLIENT_OAUTH` senza che nessuno script la creasse, → `ORA-00942`) era già stato risolto separatamente con `01-ddl-client-oauth.sql` e la rinumerazione del DML a `02-dml-client-oauth.sql`.

#### Considerazioni rinviate (da rivedere più avanti)

Non sono state applicate: la prima è codice Java e non DDL, le altre richiedono una decisione di comportamento.

**1. `BatchCaricamentoRepository.elenca` costruisce il `WHERE` con due convenzioni opposte.** Il ramo `tipoOperazione` aggiunge il parametro e **poi** decide `WHERE`/`AND` con `parametri.size() == 1`; il ramo delle date decide **prima** con `parametri.isEmpty()` e aggiunge dopo. **Tutti e quattro i casi attuali producono SQL corretto** — nessun malfunzionamento oggi, non è urgente — ma il caso "entrambi i filtri" funziona per coincidenza: il letterale `1` è hardcoded sull'assunto che quello sia il primo filtro valutato. Un terzo filtro inserito **prima** (es. `tipoEntita` o `esito`, estensioni già ipotizzate) rompe la catena: `size()` non sarebbe più `1`, nessun ramo emetterebbe `WHERE` e si otterrebbe `SELECT * FROM T_BATCH_CARICAMENTO AND TIPO_ENTITA = ?` → `ORA-00933`, come `500` a runtime solo per certe combinazioni di query param. La forma robusta è accumulare le condizioni in una `List<String>` e unirle con `String.join(" AND ", condizioni)`, premettendo `WHERE` solo se la lista non è vuota: nessun ramo deve più sapere di essere il primo.

**2. `DT_INIZIO_ELABORAZIONE` e `NOTE` sono dichiarate e mai toccate.** Compaiono **solo** nei due file DDL: zero riferimenti in tutto il codice Java, non sono nel `BatchRow` né nel `MAPPER`, resteranno `NULL` per sempre. Per `DT_INIZIO_ELABORAZIONE` c'è una ragione di merito: l'elaborazione è **sincrona**, `creaBatch` scrive `DT_RICEZIONE` e la lavorazione parte subito dopo nello stesso thread, quindi i due istanti coinciderebbero di fatto — la colonna avrebbe senso solo con un'ingestione asincrona a coda. Il danno è di chiarezza, non funzionale: il DDL promette un'informazione che non c'è. Tre opzioni, tutte legittime: lasciarle documentandole come predisposizione; popolare `DT_INIZIO_ELABORAZIONE` in `creaBatch` (una riga, e l'audit smette di avere un buco); rimuoverle con `DROP COLUMN` — sconsigliato, è l'unica che distrugge informazione.

**3. `CHECKSUM_FILE` è scritta ma mai letta: il rilevamento dei re-invii non esiste.** Il flusso è completo fino alla scrittura — `ChecksumUtils.sha256(...)`, i tre controller lo passano al service, `creaBatch` lo scrive — e lì si ferma: non è nel `MAPPER`, non compare in nessun `WHERE`, nessun confronto con i checksum precedenti. Il commento nel DDL («per individuare re-invii identici») descrive una funzione **non implementata**. La domanda aperta non è quale indice aggiungere, è **cosa deve fare il sistema se arriva due volte lo stesso file**: rifiutare con `409` (comodo contro doppio click e retry di scheduler, ma blocca un re-invio legittimo di contenuto identico); accettare segnalando il duplicato in `NOTE` (più coerente col modello append-only, che non rifiuta mai un dato); solo diagnostica, con una vista che elenca i checksum ripetuti; oppure niente, tenendo il checksum come impronta del payload utile a verificare a posteriori *cosa* era stato caricato.

Vincolo tecnico che pesa su quella scelta: il checksum è calcolato su `lista.toString()`, cioè sulla **rappresentazione Java della lista di DTO già deserializzata**, non sui byte del file. Due file con formattazione JSON diversa ma contenuto identico danno lo stesso checksum (probabilmente il comportamento desiderato), ma il valore dipende anche dall'ordine dei record e dal `toString()` dei `record`, che cambia se si aggiunge un campo a un DTO: **non è un digest stabile tra versioni dell'applicazione**, quindi un confronto con checksum storici va valutato tenendolo presente. Solo se si sceglie il confronto serve un indice su `CHECKSUM_FILE`, che diventerebbe uno script `06-`.

### Terminazioni di riga: `core.autocrlf=true` su questa postazione

Verificato il 18 agosto 2026. `git config core.autocrlf` = `true`: git **memorizza tutto in LF nell'index** e converte in CRLF al checkout. Quindi il CRLF che si vede nei file di `docs/db/` (e in gran parte del repo) non è una proprietà del repository né una convenzione della directory: è l'effetto della conversione locale. `git ls-files --eol docs/db/` mostra infatti `i/lf w/crlf` — LF nell'index, CRLF nel working tree.

Conseguenze pratiche:

- **Non serve "preservare" il CRLF** quando si modifica un file esistente. Un `sed -i` che normalizza tutto a LF produce un diff enorme nel working tree (ogni riga appare cambiata), ma `git diff` non mostra nulla di anomalo, perché il confronto avviene sul contenuto normalizzato in LF. Aggiungere un `sed -i 's/$/\r/'` per "ripristinare" i CRLF è quindi inutile ai fini del commit — al più serve se un editor o uno strumento locale si aspetta CRLF.
- `.gitattributes` forza `eol=lf` **solo** su `*.service` e `*.sh`, perché sono destinati al server Linux, dove il CRLF è un problema concreto (una unit systemd o uno script shell con CR finali non funzionano). Gli `.sql` non sono forzati: vengono eseguiti da client SQL su Windows o incollati in SQL Developer, dove il CRLF è indifferente.
- `CLAUDE.md` risulta `w/lf` invece di `w/crlf` semplicemente perché è stato scritto in LF e git non riconverte i file già presenti nel working tree; non è una convenzione diversa da rispettare.

Se un file destinato al server viene aggiunto in futuro (un nuovo `.sh`, una unit, un `entrypoint`), va aggiunta la riga corrispondente in `.gitattributes`: è l'unico punto dove la terminazione di riga conta davvero in questo progetto.
