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

`deploy/riders2eLH.service` è la unit systemd di riferimento (jar eseguito come processo standalone con Tomcat embedded, non un WAR su Tomcat esterno). I segreti non stanno mai nel repo: vanno in un `EnvironmentFile` esterno sul server (`/opt/riders2eLH/riders2eLH.env`, permessi `600`), referenziato dalla unit — `DB_PASSWORD` e `KEYSTORE_PASSWORD`.

**HTTPS**: la porta 9443 serve TLS terminato da Tomcat embedded (`server.ssl.*` in `application-local.yml`), non c'è reverse proxy davanti. Il keystore PKCS12 sta sul server in `/opt/riders2eLH/riders2eLH-keystore.p12` (permessi `600`, proprietario = utenza del servizio), generato con `keytool -genkeypair -alias riderpay -storetype PKCS12 ... -ext "SAN=ip:10.10.7.46"` — il `SAN` è necessario perché i client validano quello, non il `CN`. `keytool` non è nel `PATH` sul server: va invocato per percorso assoluto dal JRE 21 usato dalla unit. Il certificato è **self-signed**, quindi i client devono disattivare la verifica (`curl -k`, Postman: SSL certificate verification off); per uscire da dev serve un certificato della CA aziendale. Nota che attivando `server.ssl` la 9443 non risponde più in HTTP.

`deploy/README.md` contiene i comandi di setup una tantum sul server (directory, `EnvironmentFile`, unit systemd, generazione del keystore TLS). L'host di deploy è definito in `remote.deploy.host` nel `pom.xml` — quella resta la fonte autorevole in caso di dubbio.

**Rename `riderpay` → `riders2eLH` (13 agosto 2026), fatto solo a livello di build/deploy.** Sono cambiati `artifactId`/`name`/`finalName` nel `pom.xml`, il nome del jar, la unit systemd, `/opt/riders2eLH/` e il prefisso di configurazione `riders2eLH.security.jwt.*`. Tre nomi restano volutamente al vecchio valore, perché non sono etichette ma riferimenti a cose che esistono già fuori dal repo:

| Cosa | Perché non è stato rinominato |
|---|---|
| `key-alias: riderpay` (`application-local.yml`) | è l'alias inciso nel keystore PKCS12 alla generazione; cambiarlo senza rigenerare il keystore impedisce l'avvio |
| `issuer: riderpay-auth-server` (`application.yml`) | finisce nel claim `iss` dei JWT emessi: è un valore di protocollo, da concordare con chi verifica i token |
| `riderpay_deploy_key` (`remote.deploy.keyfile`) | file di chiave privata fuori dal repo, la cui pubblica è già in `authorized_keys` sul server |

Il package Java resta `it.panea.deliveroo.riderpay` e la directory del progetto resta `riderpay`: sono i punti 2 e 3 del rename, non ancora affrontati. **Sul server la migrazione va eseguita a mano** (sezione dedicata in `deploy/README.md`): finché non è fatta, `mvn deploy` scrive in `/opt/riders2eLH/` mentre systemd serve ancora il vecchio jar da `/opt/riderpay/` — nessun errore, ma le modifiche non compaiono.

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

### Struttura dei package

```
it.panea.deliveroo.riderpay/
├─ RiderPayApplication.java
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
- `BatchController` — `/batch/{idBatch}`, `/batch?tipoOperazione=...` (audit annullamenti/rettifiche)

`GlobalExceptionHandler` centralizza il mapping eccezioni → HTTP: `RisorsaNonTrovataException`→404, `ConflittoConcorrenzaException`→409, `ClientNonAutorizzatoException`→401, `MethodArgumentNotValidException`→400.

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

**`403 insufficient_scope` non è (mai) un problema di scope.** `SecurityConfig` richiede solo `.authenticated()` su `/api/v1/**` e non esiste alcun controllo di scope nel progetto: l'header `WWW-Authenticate: Bearer error="insufficient_scope"` è solo la risposta standard del `BearerTokenAccessDeniedHandler` a un accesso negato, e il riferimento agli scope è puramente formale. Corrispondenza verificata in dev il 12 agosto 2026:

| Richiesta | Risposta |
|---|---|
| nessun header `Authorization` | `401` `Bearer` |
| Bearer vuoto o `{{accessToken}}` non risolto | `401` `invalid_token` |
| **token valido + `Content-Type` non accettato dall'endpoint** | **`403` `insufficient_scope`** |

Quindi: **401 = problema di token, 403 = problema della richiesta** (tipicamente `Content-Type`), da diagnosticare nel journal e non dall'header. Il caso tipico è `POST /api/v1/voci`, che vuole `multipart/form-data` (`@RequestParam("file")`) e rifiuta JSON. In Postman va usato Body → form-data con la riga `file` di tipo File e il file **riselezionato a mano** dopo l'import (l'export della collection non incorpora i binari, e un form-data vuoto viene inviato senza `Content-Type` multipart); nessun header `Content-Type` manuale, che sovrascriverebbe il boundary generato. Verifica rapida da terminale — in PowerShell serve `curl.exe`, perché `curl` è alias di `Invoke-WebRequest` e non supporta `-H`/`-F`:

```bash
curl.exe -vk -X POST 'https://10.10.7.46:9443/api/v1/voci' -H "Authorization: Bearer $TOKEN" -F 'file=@docs/voci.csv'
```

`GlobalExceptionHandler` gestisce ora `HttpMediaTypeNotSupportedException`→415, `HttpRequestMethodNotSupportedException`→405, `HttpMessageNotReadableException`→400 e `MissingServletRequestPartException`→400, così questi errori non arrivano più al client travestiti da 403.
