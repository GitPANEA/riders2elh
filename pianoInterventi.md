# Robustezza API riders2eLH — validazione, carichi massivi, anomalie, asincronia

## Contesto

In una call col cliente sono emerse 5 richieste di robustezza sul sistema di ingestione RiderPay, in vista di casi d'uso con payload da ~30000 anagrafiche/movimentazioni per singola chiamata:

1. Validazione di formato/coerenza dei dati in input (non solo presenza).
2. Sostenibilità di carichi massivi (~30000 record per richiesta).
3. Tracciamento consultabile delle righe anomale, senza fermare l'intero batch.
4. Gestione più completa di errori/eccezioni generiche nelle API.
5. Esecuzione realmente asincrona del batch (risposta immediata con id batch, elaborazione in background, polling dello stato).

Decisioni già confermate con l'utente: persistenza del batch **in-memory** (nessuna coda esterna/Spring Batch/broker — coerente col fatto che un riavvio già invalida i JWT via `RsaKeyProvider`), dettaglio completo del record anomalo **salvato nel DB riga per riga**, regole di validazione limitate ai **formati standard italiani/anagrafici** (email, CF con checksum, CAP, telefono, coerenza date), monitoraggio **solo tramite polling REST** su `GET /api/v1/batch/{idBatch}` (nessun webhook).

Vincolo architetturale principale, già codificato nel commento a [RiderAnagraficaDto.java:19-23](src/main/java/it/panea/deliveroo/riders2elh/dto/RiderAnagraficaDto.java): la validazione "ricca" per record **non può** stare su `@Valid List<...>` nel controller, perché farebbe fallire l'intera richiesta invece di isolare il singolo record KO. Deve stare dentro il ciclo del service, prima della scrittura.

Secondo vincolo verificato nel codice: `SecurityUtils.clientIdAutenticato()` legge `SecurityContextHolder`, thread-bound. Il worker asincrono non lo erediterà: il `clientId` va risolto nel controller (thread della richiesta) e passato come parametro semplice al task asincrono — esattamente come già avviene oggi.

---

## Stato di avanzamento (aggiornato 20 agosto 2026)

- ✅ **Punto 1 — Validazione formato/coerenza** (§1): implementato. `common/ValidatoreFormato.java` + `common/RecordNonValidoException.java`, agganciati in `caricaSingolo`/`caricaSingola` dei tre service. Bug trovato e corretto in corso d'opera: il mese nel codice fiscale è una lettera sola, non due (vedi CLAUDE.md). Non implementato per scelta: regole IBAN (nessun campo nel dominio), non-negatività importi (decurtazioni legittime), formato `dd-mm-yyyy` lato Jackson per i campi `LocalDate` (resta da fare, non bloccante).
- 🟡 **Punto 2 — Carichi da 30000 record** (§2): **benchmark preliminare fatto, nessuna modifica di codice.** Deserializzazione Jackson pura (senza Spring/DB) di payload sintetici da 30000 record: `RiderAnagraficaDto` ~13MB su disco → ~330ms, +31MB heap; `MovimentazioneDto` (con liste annidate) ~39MB su disco → ~550ms, +90MB heap. Nessun problema anche forzando l'heap a 256MB. Il rischio "memoria per il parsing" segnalato dal piano non si è manifestato in questo ordine di grandezza. Dato nuovo emerso: il payload di movimentazioni pesa quasi il doppio del limite multipart di 20MB oggi configurato (che però si applica solo a `VoceController`/CSV, non a questo endpoint JSON — nessun limite esplicito lo blocca oggi). Il cliente si procurerà file di test reali di grosso volume (uno con dati puliti, uno con dati sporchi): il test end-to-end vero (parsing + scrittura su Oracle + comportamento sotto carico) resta da fare con quelli, non simulabile a tavolino.
- ✅ **Punto 3 — Righe anomale** (§3): implementato. Migrazione `NUMERO_RIGA` su `T_BATCH_CARICAMENTO_ERRORE` (eseguita manualmente su Oracle dall'utente, poi tracciata in `src/main/resources/db/migrazioni/2026-08-20_batch_errore_numero_riga.sql` e nel DDL "da zero"), `PAYLOAD_JSON` ora popolato con JSON reale (`ObjectMapper`) invece di `dto.toString()`, nuovo endpoint `GET /api/v1/batch/{idBatch}/errori?page=&size=` paginato. Dettagli in CLAUDE.md.
- 🟡 **Punto 4 — Eccezioni generiche** (§4): **4a implementato** — `MaxUploadSizeExceededException`→413 in `GlobalExceptionHandler`. **4b analizzato ma non implementato**: nessun codice scritto, solo l'analisi dei dubbi aperti (soglia, criterio di classificazione, nuovo esito batch, trattamento record non processati, strategia di recovery) — vedi tabella in §4b. Da riprendere solo dopo che quei dubbi sono stati smarcati.
- ✅ **Punto 5 — Esecuzione asincrona con polling** (§5): implementato, con conferma esplicita dell'utente sul breaking change REST. Le tre POST rispondono `202` con id batch e `esito=IN_CORSO`, elaborazione su `config/AsyncConfig.java` (`ThreadPoolTaskExecutor` dedicato, `corePoolSize=2`/`maxPoolSize=4`). Nuovi metodi `avviaCaricamento`/`elaboraAsync` nei tre service, `avviaElaborazione`/`aggiornaProgresso` in `BatchCaricamentoRepository`, soglia di aggiornamento progresso configurabile (`riders2eLH.batch.intervallo-progresso`, default 1000). **Aggiunta non prevista dal piano, decisa con l'utente durante l'implementazione**: `EsitoBatch.ERRORE_TECNICO` + `try/catch` attorno a tutto `elaboraAsync` (senza, un'eccezione fuori dal ciclo per-record sparirebbe nell'`AsyncUncaughtExceptionHandler` di Spring lasciando il batch bloccato in `IN_CORSO` per sempre) **combinato con** un campo calcolato `BatchRow.probabilmenteBloccato` (nessuna scrittura, solo in lettura su `GET /batch`, soglia configurabile `riders2eLH.batch.soglia-bloccato-minuti`, default 30) per il caso residuo in cui anche il tentativo di marcare `ERRORE_TECNICO` fallisca. Dettagli completi in CLAUDE.md.

  **Migrazione `07-ddl-batch-esito-in-corso.sql` eseguita su Oracle dev (20 agosto 2026)**: `ESITO` allargata a `VARCHAR2(20)` e `CK_BATCH_ESITO` ricreato con `IN_CORSO`/`ERRORE_TECNICO` inclusi. Applicata sia in `docs/db/07-...sql` che nella migrazione datata equivalente in `src/main/resources/db/migrazioni/`.

  **Limite noto, accettato consapevolmente (20 agosto 2026):** l'aggiornamento incrementale (`(ok+ko) % intervalloProgresso == 0`) non scatta mai per un batch con meno record della soglia (default 1000) — non è una questione di "batch troppo rapido per essere osservato": anche un batch di poche centinaia di record che impiegasse minuti (rete lenta, conflitti di concorrenza) resterebbe con `NUM_RECORD_OK`/`KO` a `NULL` durante tutta l'elaborazione, mostrando progresso solo a chiusura avvenuta. Corretto sarebbe aggiungere `|| (ok+ko) == lista.size()` per garantire almeno un aggiornamento finale prima di `chiudiBatch` — **valutato e scartato per ora**: l'utente ha giudicato accettabile lo stato attuale. Da riconsiderare se in produzione emergono batch di dimensione intermedia con tempi di elaborazione non trascurabili.

---

## 1. Validazione formato/coerenza (per record, dentro il ciclo del service)

**Nuova classe** `common/ValidatoreFormato.java`, coerente per posizione con `ChecksumUtils`/`DiagnosticaErrori` già in `common/`. Espone metodi che ritornano `List<String>` di violazioni (vuota = valido), non lanciano eccezioni:
```java
List<String> validaAnagrafica(RiderAnagraficaDto dto)
List<String> validaMovimentazione(MovimentazioneDto dto)
List<String> validaVoce(VoceDto dto)
```

**Nuova eccezione** `common/RecordNonValidoException.java` (porta la lista di violazioni), da lanciare quando la lista non è vuota.

**Punto di invocazione**: dentro `caricaSingolo`/`caricaSingola` di ciascun service (es. [AnagraficaService.java:63-70](src/main/java/it/panea/deliveroo/riders2elh/service/AnagraficaService.java)), **prima** di `sostituisciVersioneCorrente`. Il blocco `try/catch` esterno nel ciclo `for` (già presente in tutti e tre i service) resta strutturalmente invariato: la nuova eccezione finisce comunque in `registraErrore(...)`, solo con un messaggio di violazioni al posto di uno stack trace tecnico.

**Regole concrete e dove implementarle**:
- **Email**: regex pragmatica (`^[^\s@]+@[^\s@]+\.[^\s@]+$` o simile), non Bean Validation programmatica — più semplice e sufficiente per l'obiettivo "niente 'pippo'".
- **Codice fiscale**: nessuna libreria adatta in `pom.xml` né disponibile (verificato: `commons-validator` non copre il CF italiano). Implementare in `ValidatoreFormato` pattern regex 16 caratteri (`[A-Z]{6}[0-9]{2}[A-EHLMPR-T]{2}[0-9]{2}[A-Z][0-9]{3}[A-Z]`) + calcolo/verifica del carattere di controllo (algoritmo standard pari/dispari, ~40 righe, ben documentato pubblicamente). Confermato su `docs/anagrafica.json`: tutti i campioni sono CF persona fisica standard a 16 caratteri (es. `RSSMRA80A01F205X`), quindi non serve gestire il formato numerico a 11 cifre.
- **CAP**: `^[0-9]{5}$`, solo se il campo è non-blank (è opzionale oggi).
- **Telefono**: regex permissiva (`^\+?[0-9 ()-]{6,20}$` con verifica di un numero minimo di cifre), non serve `libphonenumber`.
- **Coerenza `periodoDa` ≤ `periodoA`**: check diretto in `validaMovimentazione` ([MovimentazioneDto.java:15-16](src/main/java/it/panea/deliveroo/riders2elh/dto/MovimentazioneDto.java)). Anticipa con messaggio leggibile l'eventuale `CK_MOV_PERIODO` pianificato ma non ancora eseguito su Oracle.
- **Formati data**: due pattern distinti secondo la granularità del campo, non un solo formato universale. Verificati i tipi reali nei DTO:
  - campi con giorno — `periodoDa`/`periodoA` in [MovimentazioneDto.java:15-16](src/main/java/it/panea/deliveroo/riders2elh/dto/MovimentazioneDto.java) e `data` in [ConsegnaDto.java:9](src/main/java/it/panea/deliveroo/riders2elh/dto/ConsegnaDto.java) sono già tipizzati `LocalDate` (non `String`): il formato **`dd-mm-yyyy`** in ingresso va quindi imposto a livello di deserializzazione Jackson (`@JsonFormat(pattern = "dd-MM-yyyy")` sul campo, oppure un `ObjectMapper`/`JavaTimeModule` configurato globalmente con quel pattern come default), non nel validator per-record — un valore che non rispetta il formato fallisce già in fase di parsing del JSON con `HttpMessageNotReadableException` (già gestita da `GlobalExceptionHandler`→400), prima ancora di arrivare al service. Il validator si limita quindi a controllare la coerenza *tra* le due date già convertite (`periodoDa` ≤ `periodoA`), non il loro formato di stringa.
  - campo mese/anno — `meseRiferimento` in [VoceMovimentazioneDto.java:10](src/main/java/it/panea/deliveroo/riders2elh/dto/VoceMovimentazioneDto.java) è invece una `String` libera (non tipizzata come data): qui il formato **`mm-yyyy`** va validato per-record in `ValidatoreFormato` con regex `^(0[1-9]|1[0-2])-\d{4}$`, applicata solo se il campo è non-null (è opzionale). Nota: `meseRiferimentoRichiesto` in `VoceDto.java:10` è un `Boolean` (flag "richiede il mese di riferimento"), non un campo data — non rientra in questa regola.
- **IBAN**: nessun campo IBAN esiste in alcun DTO né nel DDL — non applicabile a questo dominio, va segnalato al cliente come tale.
- **Importi `BigDecimal`**: nessun vincolo di non-negatività (le decurtazioni sono legittimamente negative, già chiarito in CLAUDE.md) — nessuna azione.

Nessuna annotazione va aggiunta ai DTO stessi; i `@Size` già presenti restano (prevengono troncamento Oracle, sono ortogonali al formato).

---

## 2. Carichi da ~30000 record

- **Multipart** (`application.yml:23-26`, 20MB): si applica solo a `VoceController` (CSV). 30000 righe di voci restano ben sotto soglia; non risulta necessario alzarlo, ma va confermato con una stima concreta della dimensione media riga.
- **JSON body** (`AnagraficaController`/`MovimentazioneController`, `@RequestBody`): non soggetto ai limiti multipart. Il vincolo reale è la memoria heap per deserializzare 30000 oggetti (con liste annidate per le movimentazioni) — da verificare con un **test di carico con payload sintetico da 30000 record** prima del rilascio, non stimabile solo a tavolino.
- **Timeout HTTP**: con l'asincronia (punto 5) il problema sparisce per l'elaborazione (la POST risponde subito); resta solo il tempo di parsing JSON pre-202, verosimilmente nell'ordine di centinaia di ms.
- **Pool Hikari vs pool executor batch**: `maximum-pool-size: 20` in entrambi i profili. Ogni record del batch apre/chiude la propria transazione (`sostituisciVersioneCorrente` è `@Transactional` per singolo record, non per l'intero batch), quindi le connessioni si liberano rapidamente. Raccomandazione: pool executor **piccolo** (`corePoolSize` 2-4), lasciando margine su Hikari per il traffico REST sincrono normale (GET/DELETE/rettifiche). Non serve alzare Hikari per il caso d'uso attuale (un batch alla volta, non elaborazione parallela di più batch).

**Dato nuovo dal cliente (20 agosto 2026): i file anagrafica possono pesare fino a ~100MB.** Non è un limite che oggi *blocca* la richiesta (`spring.servlet.multipart.*` non si applica a `POST /anagrafiche`, che è `@RequestBody` JSON, non multipart — quel limite riguarda solo `POST /voci`/CSV, dove invece scatterebbe un 413 già gestito). Il rischio è tutto sulla **memoria heap** per la deserializzazione Jackson: dal benchmark fatto nella sessione precedente (30000 record ≈ 13MB → +31MB heap, rapporto ~2.5x), un file da 100MB potrebbe tradursi in ~250MB di heap aggiuntivo solo per il parsing, prima di iniziare a scrivere su Oracle — **non testato con un file di quella dimensione reale**, solo stimato per proporzione.

Non calcolabile con certezza senza un file di test reale: il rapporto dimensione/heap osservato sui 30000 record sintetici potrebbe non essere lineare a 100MB (strutture Jackson intermedie, GC pressure, frammentazione), e dipende anche da quanta RAM ha davvero il server dev (`10.10.7.46`, condiviso con altri servizi, nessun `-Xmx` esplicito configurato nella unit systemd). **Decisione: aspettare i file di test reali del cliente prima di ogni intervento di codice** — non ha senso ottimizzare (streaming parser, `-Xmx` dedicato) su un rischio ancora solo stimato.

**Opzione valutata e sospesa: recuperare il file via FTPS invece che nel body della POST.** Risolverebbe il vincolo di trasporto HTTP (niente rischio di timeout/instabilità lato client durante l'upload di 100MB), ma **non risolve il problema di memoria**: una volta scaricati i byte da FTPS, la deserializzazione Jackson consuma lo stesso heap indipendentemente dal canale con cui sono arrivati — il collo di bottiglia è nel parsing, non nel trasporto. Introdurrebbe inoltre complessità non banale (nuovo endpoint trigger, credenziali FTPS da gestire con un `EnvironmentFile` dedicato, dipendenza nuova in `pom.xml` per un client FTPS, tracciabilità del batch più complessa perché il download diventa asincrono rispetto alla chiamata API). **Non richiesto esplicitamente dal cliente** — hanno solo segnalato la dimensione del file, non un problema di canale di trasferimento. Da riconsiderare solo se, chiarendo con loro, emerge che il problema reale è l'upload HTTP in sé (es. hanno già avuto timeout/instabilità), non la capacità del server di elaborarlo.
- **Parsing incrementale**: il CSV voci è già letto in streaming (`commons-csv`, [VoceController.java:64-73](src/main/java/it/panea/deliveroo/riders2elh/api/VoceController.java)). Per JSON, Jackson deserializza l'intero array prima dell'invocazione del controller — non necessariamente da riscrivere con `JsonParser` a basso livello a meno che il test di carico dimostri un problema reale.

Azione concreta: nessuna modifica di configurazione obbligatoria a priori; **test di carico da 30000 record** come verifica preliminare, e dimensionamento conservativo dell'executor pool (deciso al punto 5).

---

## 3. Righe anomale: tracciamento e consultazione

**Migrazione DDL** — nuovo file datato in `src/main/resources/db/migrazioni/` (stessa convenzione di `2026-08-13_anagrafica_contatti.sql`), replicato in `docs/db/` (prossimo numero dopo `05-ddl-check-coerenza.sql`) e nel DDL "da zero" `src/main/resources/db/ddl_riderpay.sql`:

```sql
ALTER TABLE T_BATCH_CARICAMENTO_ERRORE ADD NUMERO_RIGA NUMBER;
```
Nullable, posizione del record nel payload originale — utile quando `CHIAVE_BUSINESS` è essa stessa mancante/malformata (es. `id_rider` assente).

**`PAYLOAD_JSON` reale invece di `dto.toString()`**: nei tre service, la chiamata a `batchRepository.registraErrore(...)` passa oggi `dto.toString()` (rappresentazione Java). Va sostituita con `objectMapper.writeValueAsString(dto)` (Jackson `ObjectMapper`, già disponibile via `spring-boot-starter-web`, da iniettare nei tre service). Cambio additivo: chi consulta `T_BATCH_CARICAMENTO_ERRORE` guadagna un payload realmente parsabile.

Per popolare `NUMERO_RIGA`, il ciclo `for (RiderAnagraficaDto dto : lista)` nei tre service diventa indicizzato (o `IntStream.range`) — modifica localizzata.

**Nuovo endpoint** `GET /api/v1/batch/{idBatch}/errori` in `BatchController.java` (oggi non esiste alcun modo di consultare le singole righe anomale, solo il `BatchRow` aggregato):
- nuovo `BatchErroreRow` record in `repository/` (pattern coerente con `BatchRow`);
- nuovo metodo `elencaErrori(idBatch)` in `BatchCaricamentoRepository` con `RowMapper` dedicato;
- nuovo metodo in `BatchQueryService` che verifica prima l'esistenza del batch (404 se assente, altrimenti 200 anche con lista vuota — coerente con la convenzione già in uso su `GET /api/v1/batch`);
- **paginazione** (`?page=&size=`, default ragionevole es. 100) da considerare fin da subito: un batch da 30000 record può generare fino a 30000 righe di errore nel caso peggiore, e senza paginazione la risposta sarebbe enorme.

---

## 4. Eccezioni generiche nelle API

**a) `MaxUploadSizeExceededException` → 413** — oggi assente, cade nel catch-all `Exception`→500. Nuovo `@ExceptionHandler` in `GlobalExceptionHandler.java`, vicino agli altri handler specifici (es. accanto a `HttpMediaTypeNotSupportedException`), rilevante per `VoceController` (unico endpoint multipart).

**b) Differenziazione errori "di dato" vs "tecnici"** — oggi `catch (Exception e)` indifferenziato in tutti e tre i service (es. [AnagraficaService.java:56](src/main/java/it/panea/deliveroo/riders2elh/service/AnagraficaService.java)): un `ORA-01950`/DB irraggiungibile produce N righe di errore identiche, limite già documentato in CLAUDE.md. Proposta (**da confermare esplicitamente col cliente prima di implementare, è un cambio di comportamento non solo di codice**): distinguere `RecordNonValidoException` (errore di dato, sempre isolato, comportamento attuale) da `DataAccessException` non riconducibile a vincolo di integrità (sospetto errore infrastrutturale); dopo N errori "tecnici" consecutivi (soglia configurabile), interrompere il ciclo e chiudere il batch con una singola riga di errore che segnala l'interruzione, invece di proseguire fino a 30000 righe duplicate. Non blocca il rilascio degli altri punti — è un affinamento indipendente, proposto come Fase 3.

**Analisi 20 agosto 2026 — dubbi aperti, nessuna implementazione fatta.** Prima di poter scrivere codice restano da smarcare:

| Decisione | Proposta di default | Vincolante? |
|---|---|---|
| Criterio di classificazione | `RecordNonValidoException`/`DataIntegrityViolationException` = errore di dato (sempre isolato); qualunque altra `DataAccessException` = sospetto tecnico | Ragionevole, basso rischio — ma non è una certezza: una `DataAccessException` generica potrebbe comunque derivare da un problema specifico del singolo record |
| Soglia errori tecnici consecutivi | 5-10, configurabile via `application.yml` (non cablata) | Da confermare/tarare col cliente — nessun numero è "tecnicamente corretto", è un trade-off tra fermarsi troppo presto su un blip transitorio (es. l'`ORA-01950` già documentato, spesso transitorio) e fermarsi troppo tardi vanificando lo scopo |
| Contatore consecutivo, non cumulativo | Il contatore si azzera a ogni record che va a buon fine o fallisce per errore di dato | Necessario per non penalizzare un batch con pochi blip isolati sparsi su 30000 record |
| Nuovo esito batch interrotto | Nuovo valore enum, es. `EsitoBatch.INTERROTTO` — un batch fermato a metà per causa tecnica non è né `KO` né `PARZIALE` nel senso attuale | Da decidere |
| Trattamento dei record non processati (quelli dopo il punto di interruzione) | Solo conteggio (`numRecordTotali > numRecordOk + numRecordKo` segnala l'interruzione), nessuna riga per record mai tentato | Da decidere — alternativa: elencarli comunque in `T_BATCH_CARICAMENTO_ERRORE` con un messaggio distinto da "record non valido" |
| Strategia di recovery per il cliente | Rimanda l'intero file, oppure solo dalla riga di interruzione in avanti (richiede comunicare da dove si sono fermati, oggi possibile grazie a `NUMERO_RIGA`) | Da decidere — impatta cosa restituire nella risposta/nel batch |

Nessuna di queste ha una risposta deducibile dal codice: sono scelte di comportamento del prodotto. Restano sospese finché non vengono confermate.

---

## 5. Esecuzione asincrona con polling

**Executor**: nuovo `config/AsyncConfig.java` con `@EnableAsync` + bean `ThreadPoolTaskExecutor` dedicato (`corePoolSize`/`maxPoolSize` piccoli, 2-4, come discusso al punto 2). Nessuna nuova dipendenza (`@Async` è core Spring, già in classpath).

**Nuovo stato `IN_CORSO`**: aggiunto a `common/EsitoBatch.java` (oggi `{OK, KO, PARZIALE}`). Confermato: esiste già un `CHECK` su `ESITO` (`CK_BATCH_ESITO` in `docs/db/05-ddl-check-coerenza.sql:63-64`, `ESITO IS NULL OR ESITO IN ('OK','KO','PARZIALE')`) — va **ricreato** (`DROP CONSTRAINT` + nuovo `CHECK` che include `'IN_CORSO'`) in una nuova migrazione datata, replicata in tutti e tre i posti DDL come sopra. Scrivere `IN_CORSO` esplicitamente (invece di lasciare `NULL`) distingue "mai iniziato" da "in corso", senza aggiungere una colonna.

**Flusso**:
1. Controller: crea il batch (`creaBatch`, esistente), risolve `clientId` (sul thread della richiesta), avvia il task `@Async` passando i parametri già risolti, risponde immediatamente **`202 Accepted`** con solo `idBatch` (contatori assenti/a zero, stato `IN_CORSO`).
2. Task asincrono: prima operazione, `UPDATE` che scrive `DT_INIZIO_ELABORAZIONE` + `ESITO='IN_CORSO'` (nuovo metodo `avviaElaborazione(idBatch)` in `BatchCaricamentoRepository` — finalmente popola una colonna che esiste da sempre nel DDL ma non è mai stata scritta). Poi il ciclo `for` esistente, invariato nella struttura. Chiusura finale con `chiudiBatch` come oggi.
3. **Aggiornamento incrementale dei contatori**: ogni N record (proposta: 1000, o soglia configurabile via `riders2eLH.batch.intervallo-progresso` in `application.yml`) un `UPDATE` parziale (`aggiornaProgresso`, nuovo metodo) su `NUM_RECORD_OK/KO` — compromesso tra un polling che mostra progresso reale e il costo di scrittura extra (30-60 update aggiuntivi su un batch da 30000, trascurabile rispetto ai 30000 insert/update già previsti). Alternativa più economica (nessun aggiornamento incrementale, solo stato IN_CORSO + risultato finale) è possibile ma soddisfa solo la lettera del requisito "monitorare l'avanzamento", non la richiesta di sapere "quanto manca" — la proposta a step è il default consigliato.

**Cambio di contratto REST — breaking change da comunicare esplicitamente**: la POST oggi risponde `201`/`207` con `BatchEsitoResponse` completo (contatori finali) in modo sincrono. Con l'asincronia risponde `202` con id batch e stato non definitivo. Qualunque client che legga oggi i contatori direttamente dalla risposta della POST deve essere aggiornato per fare polling su `GET /api/v1/batch/{idBatch}` (endpoint già esistente, nessun nuovo endpoint necessario per il polling in sé).

**File coinvolti**: i tre service (`AnagraficaService`, `VoceService`, `MovimentazioneService` — split tra parte sincrona di avvio e parte `@Async` di elaborazione), i tre controller (risposta `202`), `BatchCaricamentoRepository` (nuovi metodi `avviaElaborazione`/`aggiornaProgresso`), `common/EsitoBatch.java`, nuova migrazione DDL per il `CHECK`, annotazioni OpenAPI aggiornate (`@ApiResponses` con `202` al posto di `201`/`207`).

---

## Fasizzazione

**Fase 1 — additiva, nessun breaking change:**
1. ✅ Validazione formato/coerenza per record (§1) — implementato.
2. ✅ Estensione `T_BATCH_CARICAMENTO_ERRORE` (`NUMERO_RIGA`, `PAYLOAD_JSON` reale) + nuovo endpoint `GET /api/v1/batch/{idBatch}/errori` con paginazione (§3) — implementato.
3. ✅ `MaxUploadSizeExceededException` → 413 (§4a) — implementato.
4. 🟡 Test di carico con payload sintetico da 30000 record (§2) — benchmark preliminare Jackson fatto (vedi Stato di avanzamento), nessuna modifica di codice. Test end-to-end reale in attesa dei file di test del cliente.

**Fase 2 — richiede coordinamento col cliente (breaking change sul contratto REST):**
5. ✅ Esecuzione asincrona: POST → `202` + polling, nuovo stato `IN_CORSO`, aggiornamento incrementale contatori (§5) — implementato, cliente già d'accordo sul breaking change.

**Fase 3 — miglioramento comportamentale, da concordare separatamente:**
6. Differenziazione errori di dato vs tecnici, con eventuale interruzione anticipata dopo N errori tecnici consecutivi (§4b).

---

## Verifica end-to-end

- **Unitaria manuale**: per ogni nuova regola di `ValidatoreFormato`, un batch di anagrafiche con record contenenti email non valida, CF con checksum errato, CAP a 4 cifre, periodo invertito — verificare che ogni record diventi una riga isolata in `T_BATCH_CARICAMENTO_ERRORE` (via il nuovo `GET /errori`) senza bloccare gli altri record del batch.
- **Carico**: generare un JSON sintetico da 30000 anagrafiche/movimentazioni (script o `docs/anagrafica.json` replicato), inviarlo con Postman/curl, misurare tempo di risposta della POST (deve essere immediato dopo la Fase 2) e tempo totale di completamento via polling su `GET /api/v1/batch/{idBatch}`.
- **Asincronia**: verificare che due chiamate concorrenti (es. anagrafiche + movimentazioni) non esauriscano il pool Hikari (osservare `USER_TS_QUOTAS`/log Hikari o semplicemente il comportamento sotto carico), e che `DT_INIZIO_ELABORAZIONE`/`ESITO='IN_CORSO'` siano visibili tramite polling prima della chiusura del batch.
- **Regressione**: rieseguire le chiamate esistenti della collection Postman (`docs/riderpay.postman_collection.json`) aggiornata con i nuovi status code attesi, per verificare che gli endpoint GET/DELETE/rettifica non siano stati impattati.
