# Riepilogo sessione — riderpay

Riepilogo del lavoro svolto in questa chat, per continuare in una nuova sessione senza perdere contesto.

## 1. Analisi iniziale del progetto

La cartella conteneva solo documentazione/progettazione (nessun codice sorgente): `anagrafica.json`, `voci.csv`, `movimentazioni.json` (dati di esempio), `progettazione_ingestion_oracle.md` (design completo con codice Java "di riferimento" incorporato), `ddl_riderpay.sql`, `riderpay.postman_collection.json`. Tutti ora spostati/organizzati sotto `docs/`.

## 2. Generazione del progetto Spring Boot

Estratto il codice Java dal documento di progettazione in un vero progetto Maven compilabile:
- Java 21, Spring Boot 3.3.4, `spring-boot-starter-web`/`jdbc`/`validation`, `ojdbc11`, `commons-csv`.
- Package `common/`, `dto/`, `repository/` (JdbcTemplate puro, no JPA), `service/`, `api/`.
- 4 controller REST (`AnagraficaController`, `VoceController`, `MovimentazioneController`, `BatchController`) implementano il modello **append-only/SCD Type 2** descritto nel documento: nessun UPDATE/DELETE fisico sui dati di business, storicizzazione con `FLAG_ULTIMA_VERSIONE`/`STATO_RECORD`/`DT_INSERIMENTO`/`ID_BATCH_CARICAMENTO`.

## 3. Fix sintattici sul DDL

- `DEFAULT "CARICAMENTO"` → `DEFAULT 'CARICAMENTO'` (apici doppi non validi per stringhe Oracle).
- Ordine `NOT NULL DEFAULT 'x'` → `DEFAULT 'x' NOT NULL` (Oracle richiede DEFAULT prima di NOT NULL), corretto in 13 punti del DDL. Causa reale di un `ORA-00907` che sembrava un problema di parentesi/apostrofi ma non lo era.

## 4. Autenticazione OAuth2 Client Credentials

Aggiunta un'implementazione **manuale leggera** (non Spring Authorization Server completo, ritenuto overkill per puro client_credentials machine-to-machine):
- Nuovo package `config/security/`: `RsaKeyProvider` (chiave RSA generata in memoria all'avvio — **non persistita**, un riavvio invalida i token emessi), `JwtTokenService`, `SecurityConfig` (SecurityFilterChain: `/oauth2/token` pubblico, `/api/v1/**` autenticato via JWT).
- Nuova tabella Oracle `T_CLIENT_OAUTH` (client_id, client_secret hash bcrypt, scope, TTL).
- `TokenController` (`POST /oauth2/token`, grant_type=client_credentials, Basic Auth o form-urlencoded).
- Refactor dei 4 controller: rimosso l'header libero `X-Client-Id`, sostituito con `SecurityUtils.clientIdAutenticato()` che legge il claim `sub` del JWT verificato.
- Client di test registrato: `client_id=riders2elh-test`, secret generato casualmente (salvato solo in Postman/appunti utente, hash bcrypt nel DDL).
- Collection Postman aggiornata: cartella "00 - Autenticazione" con script che salva `access_token` in variabile di collezione, Bearer token a livello di collezione, rimosso `X-Client-Id` da tutte le richieste.

## 5. Deploy su ambiente dev (replica schema di gpapi)

Analizzato il progetto `gpapi` (deploy manuale via `maven-antrun-plugin` + task Ant `<scp>`, profili Maven `dev`/`prod`). Replicato **solo il profilo dev** per riderpay (jar standalone con Tomcat embedded, non WAR):
- `pom.xml`: profilo Maven `dev` (attivo di default), proprietà di deploy, plugin `maven-antrun-plugin` con SCP, `finalName=riderpay`.
- `deploy/riderpay.service` — unit systemd per eseguire il jar come processo standalone (`User=f.cavaliere`, `EnvironmentFile` esterno per `DB_PASSWORD`, mai versionato).
- `deploy/README.md` — istruzioni di setup una tantum sul server e comandi post-deploy.

### Problemi di infrastruttura risolti durante il deploy
- Chiave SSH iniziale (`svc-claude.key`) risultata corrotta/non valida (verificato con `ssh-keygen -l`, errore "not a key file") anche dopo sostituzione dalla stessa fonte → **generata una nuova coppia RSA 4096** (`riderpay_deploy_key`, già in formato PEM classico compatibile con JSch 0.1.55) e autorizzata sul server.
- Host cambiato più volte: `10.10.7.14` → `10.10.7.46` (quest'ultimo è l'host applicativo reale, hostname `microservices-with-gui`, condiviso con altri servizi).
- Java sul server era 16/8/11, mancava Java 21 → installato `java-21-openjdk` via `dnf`, path binario esplicito in `ExecStart` della unit.
- Porta 8080 e 8081 già occupate da altri processi Java sulla macchina condivisa; **8180 non era whitelisted nel firewall** (`firewalld` con whitelist esplicita: 8080/8081/8443/8444/9443). Spostata l'app sulla porta **9443** (già libera e già aperta nel firewall) — configurato in `application-local.yml` (`server.port: 9443`).

## 6. Bug applicativo: colonne con DEFAULT Oracle ignorate da SimpleJdbcInsert

**Non ancora risolto del tutto — questo è il punto da riprendere nella prossima chat.**

`SimpleJdbcInsert` (usato in `RiderAnagraficaRepository`, `VoceRepository`, `MovimentazioneRepository`), quando una colonna non è nella `Map` passata, in questo ambiente **non la omette dallo statement come da comportamento standard documentato**, ma genera un tentativo di insert con valore NULL, causando `ORA-01400` su colonne con `DEFAULT` lato Oracle:
- Prima scoperta: `DT_INSERIMENTO` (colonna di `PARTITION BY RANGE`) → fix: impostato esplicitamente `Timestamp.from(Instant.now())`.
- Seconda scoperta: `FLAG_ULTIMA_VERSIONE` (non di partizionamento, stesso sintomo) → fix: impostato esplicitamente `"S"`.
- Verificate tutte le altre colonne con DEFAULT nel DDL (17 occorrenze totali): le altre non sono a rischio perché non passano da `SimpleJdbcInsert` (usano SQL diretto o sono su tabelle senza insert da codice).

**Dopo questi due fix, è apparso un nuovo errore**: `PreparedStatementCallback; bad SQL grammar []` — messaggio troppo generico per diagnosticare (nessun codice ORA visibile, nessuno stack trace utile né nei log systemd né in `T_BATCH_CARICAMENTO_ERRORE.MESSAGGIO_ERRORE`). Sospetto iniziale (record `f1ca91d2`/Anna Neri isolato) **scartato**: una query di verifica (`SELECT ID_RIDER FROM T_RIDER_ANAGRAFICA_ST WHERE FLAG_ULTIMA_VERSIONE = 'S'`) ha restituito **zero righe**, quindi nessuno dei 4 record del batch è stato inserito con successo — il problema è sistemico, non isolato a un record.

**Ultima azione intrapresa**: abilitato logging DEBUG (`org.springframework.jdbc: DEBUG`, `org.springframework.jdbc.core.simple.SimpleJdbcInsert: DEBUG`) in `application-local.yml`, già deployato sul server. **Prossimo passo**: riavviare il servizio (`sudo systemctl restart riderpay`), rifare il flusso (token → `POST /anagrafiche`), e leggere `sudo journalctl -u riderpay -f` durante la chiamata per catturare l'SQL esatto generato e l'errore Oracle completo — dato non ancora raccolto.

## File chiave modificati in questa sessione

- `pom.xml`, `docs/ddl_riderpay.sql` + copia in `src/main/resources/db/`
- `src/main/resources/application.yml`, `application-local.yml`
- `src/main/java/.../repository/{RiderAnagraficaRepository,VoceRepository,MovimentazioneRepository,ClientOAuthRepository,ClientOAuthRow}.java`
- `src/main/java/.../config/security/{SecurityConfig,RsaKeyProvider,JwtTokenService}.java`
- `src/main/java/.../api/{TokenController,GlobalExceptionHandler,AnagraficaController,VoceController,MovimentazioneController,BatchController}.java`
- `src/main/java/.../common/{SecurityUtils,ClientNonAutorizzatoException}.java`
- `deploy/riderpay.service`, `deploy/README.md`
- `docs/riderpay.postman_collection.json`

## Credenziali/parametri d'ambiente attivi

- Server dev: `10.10.7.46` (hostname `microservices-with-gui`), utente SSH `f.cavaliere`, chiave `C:\Sirfin Documents\ProdKey\riderpay_deploy_key`.
- App in esecuzione su porta **9443**, systemd service `riderpay`, jar in `/opt/riderpay/riderpay.jar`, env file `/opt/riderpay/riderpay.env` (contiene `DB_PASSWORD`).
- DB Oracle dev: `10.10.7.187:1521/SVILUPPO.testsub.prod.oraclevcn.com`, utente `CDL0036`.
- Client OAuth di test: `client_id=riders2elh-test`, secret in Postman (`clientSecret` variabile di collezione).
