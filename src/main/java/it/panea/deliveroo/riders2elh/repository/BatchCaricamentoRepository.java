package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.EsitoBatch;
import it.panea.deliveroo.riders2elh.common.TipoEntita;
import it.panea.deliveroo.riders2elh.common.TipoOperazione;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BatchCaricamentoRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insertBatch;

    public BatchCaricamentoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertBatch = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_BATCH_CARICAMENTO")
                .usingGeneratedKeyColumns("ID_BATCH");
    }

    /** § 9.1 passo 1 / § 9.3.1 passo 2: apre un batch di CARICAMENTO, RETTIFICA o ANNULLAMENTO. */
    public long creaBatch(TipoEntita tipoEntita, TipoOperazione tipoOperazione, Long idBatchRiferimento,
                           String motivoOperazione, String nomeFileOrigine, String formatoFile,
                           String clientId, String checksumFile) {
        Map<String, Object> valori = new HashMap<>();
        valori.put("TIPO_ENTITA", tipoEntita.name());
        valori.put("TIPO_OPERAZIONE", tipoOperazione.name());
        valori.put("ID_BATCH_RIFERIMENTO", idBatchRiferimento);
        valori.put("MOTIVO_OPERAZIONE", motivoOperazione);
        valori.put("NOME_FILE_ORIGINE", nomeFileOrigine);
        valori.put("FORMATO_FILE", formatoFile);
        valori.put("CLIENT_ID", clientId);
        valori.put("DT_RICEZIONE", Timestamp.from(Instant.now()));
        valori.put("CHECKSUM_FILE", checksumFile);
        return insertBatch.executeAndReturnKey(valori).longValue();
    }

    public void chiudiBatch(long idBatch, EsitoBatch esito, int totali, int ok, int ko) {
        jdbcTemplate.update("""
                UPDATE T_BATCH_CARICAMENTO
                   SET DT_FINE_ELABORAZIONE = ?, ESITO = ?, NUM_RECORD_TOTALI = ?, NUM_RECORD_OK = ?, NUM_RECORD_KO = ?
                 WHERE ID_BATCH = ?
                """, Timestamp.from(Instant.now()), esito.name(), totali, ok, ko, idBatch);
    }

    /**
     * Prima operazione del task asincrono: segna l'inizio effettivo dell'elaborazione,
     * distinto da DT_RICEZIONE (quando la richiesta HTTP è arrivata) perché con
     * l'esecuzione asincrona i due istanti possono differire se l'executor è occupato.
     */
    public void avviaElaborazione(long idBatch, int totali) {
        jdbcTemplate.update("""
                UPDATE T_BATCH_CARICAMENTO
                   SET DT_INIZIO_ELABORAZIONE = ?, ESITO = ?, NUM_RECORD_TOTALI = ?
                 WHERE ID_BATCH = ?
                """, Timestamp.from(Instant.now()), EsitoBatch.IN_CORSO.name(), totali, idBatch);
    }

    /**
     * Aggiornamento incrementale dei contatori durante l'elaborazione, per un polling
     * che mostri progresso reale. Chiamato ogni N record (soglia configurabile) e non a
     * ogni record: un UPDATE per record raddoppierebbe il carico di scrittura sul batch
     * per un beneficio di sola leggibilità del polling.
     */
    public void aggiornaProgresso(long idBatch, int ok, int ko) {
        jdbcTemplate.update("""
                UPDATE T_BATCH_CARICAMENTO SET NUM_RECORD_OK = ?, NUM_RECORD_KO = ? WHERE ID_BATCH = ?
                """, ok, ko, idBatch);
    }

    public void registraErrore(long idBatch, Integer numeroRiga, String chiaveBusiness, String messaggioErrore, String payloadJson) {
        jdbcTemplate.update("""
                INSERT INTO T_BATCH_CARICAMENTO_ERRORE (ID_BATCH, NUMERO_RIGA, CHIAVE_BUSINESS, MESSAGGIO_ERRORE, PAYLOAD_JSON)
                VALUES (?, ?, ?, ?, ?)
                """, idBatch, numeroRiga, chiaveBusiness, messaggioErrore, payloadJson);
    }

    /**
     * GET /api/v1/batch/{idBatch}/errori — righe anomale di un batch, in ordine di
     * inserimento, paginate: un batch da 30000 record può generare fino a 30000 righe
     * di errore, quindi nessuna risposta senza paginazione.
     */
    public List<BatchErroreRow> elencaErrori(long idBatch, int page, int size) {
        return jdbcTemplate.query("""
                SELECT * FROM T_BATCH_CARICAMENTO_ERRORE WHERE ID_BATCH = ?
                 ORDER BY ID_BATCH_ERRORE
                 OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """, MAPPER_ERRORE, idBatch, page * size, size);
    }

    public Optional<BatchRow> trovaPerId(long idBatch) {
        return jdbcTemplate.query("SELECT * FROM T_BATCH_CARICAMENTO WHERE ID_BATCH = ?", MAPPER, idBatch)
                .stream().findFirst();
    }

    /**
     * GET /api/v1/batch?tipoOperazione=...&amp;dataInizio=...&amp;dataFine=... — ogni filtro è
     * opzionale (null = non applicato) e i filtri sono combinabili tra loro.
     * <p>
     * Il filtro per data seleziona l'intervallo semiaperto
     * {@code [dataInizio 00:00:00, dataFine+1giorno 00:00:00)}: il {@code <} stretto
     * sull'estremo superiore, unito al giorno aggiunto, include tutto il giorno
     * {@code dataFine} fino a {@code 23:59:59.999...} — quindi <b>entrambi gli estremi
     * dell'intervallo di giorni sono inclusi</b>. È l'equivalente di
     * {@code TRUNC(DT_RICEZIONE) BETWEEN TRUNC(dataInizio) AND TRUNC(dataFine)}, scritto in
     * modo da restare sargable: una funzione sulla colonna ({@code TRUNC(DT_RICEZIONE)})
     * impedirebbe l'uso di un eventuale indice su {@code DT_RICEZIONE}, che oggi non esiste
     * ma su un range scan è esattamente il caso in cui servirebbe.
     * <p>
     * <b>Nessuna conversione di fuso orario</b>: gli estremi sono costruiti da
     * {@link LocalDate} così come arrivano e confrontati con {@code DT_RICEZIONE}, che è un
     * {@code TIMESTAMP} Oracle senza fuso. Il giorno di ricerca è quindi il giorno come sta
     * scritto nella colonna — scelta esplicita (13 agosto 2026), perché {@code DT_RICEZIONE}
     * è considerata il valore autoritativo. Se la JVM del server non è in {@code Europe/Rome},
     * un batch ricevuto poco dopo la mezzanotte italiana risulta archiviato nel giorno
     * precedente, e va cercato in quel giorno.
     */
    public List<BatchRow> elenca(TipoOperazione tipoOperazione, LocalDate dataInizio, LocalDate dataFine) {
        StringBuilder sql = new StringBuilder("SELECT * FROM T_BATCH_CARICAMENTO");
        List<Object> parametri = new ArrayList<>();

        if (tipoOperazione != null) {
            parametri.add(tipoOperazione.name());
            sql.append(parametri.size() == 1 ? " WHERE" : " AND").append(" TIPO_OPERAZIONE = ?");
        }
        if (dataInizio != null && dataFine != null) {
            sql.append(parametri.isEmpty() ? " WHERE" : " AND").append(" DT_RICEZIONE >= ? AND DT_RICEZIONE < ?");
            parametri.add(Timestamp.valueOf(dataInizio.atStartOfDay()));
            parametri.add(Timestamp.valueOf(dataFine.plusDays(1).atStartOfDay()));
        }

        sql.append(" ORDER BY DT_RICEZIONE DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, parametri.toArray());
    }

    /**
     * Le colonne numeriche usano {@code getObject(nome, Classe)} e non un cast su
     * {@code getObject(nome)}: il driver Oracle restituisce {@code NUMBER} come
     * {@link java.math.BigDecimal}, quindi {@code (Integer) rs.getObject(...)} solleva
     * {@link ClassCastException}. Il difetto resta latente sui batch ancora aperti (colonne
     * NULL, il cast passa) e si manifesta solo leggendo un batch chiuso, con i contatori
     * valorizzati. Non si usa {@code getInt}/{@code getLong} perché restituiscono 0 sui NULL,
     * falsando i contatori e {@code ID_BATCH_RIFERIMENTO}.
     */
    /**
     * probabilmenteBloccato è sempre false qui: dipende da una soglia di tempo, decisione
     * applicativa e non di mapping — calcolato da BatchQueryService dopo la lettura.
     */
    private static final RowMapper<BatchRow> MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("ID_BATCH"),
            TipoEntita.valueOf(rs.getString("TIPO_ENTITA")),
            TipoOperazione.valueOf(rs.getString("TIPO_OPERAZIONE")),
            rs.getObject("ID_BATCH_RIFERIMENTO", Long.class),
            rs.getString("MOTIVO_OPERAZIONE"),
            rs.getString("NOME_FILE_ORIGINE"),
            rs.getString("CLIENT_ID"),
            rs.getTimestamp("DT_RICEZIONE").toInstant(),
            rs.getTimestamp("DT_INIZIO_ELABORAZIONE") != null ? rs.getTimestamp("DT_INIZIO_ELABORAZIONE").toInstant() : null,
            rs.getTimestamp("DT_FINE_ELABORAZIONE") != null ? rs.getTimestamp("DT_FINE_ELABORAZIONE").toInstant() : null,
            rs.getString("ESITO") != null ? EsitoBatch.valueOf(rs.getString("ESITO")) : null,
            rs.getObject("NUM_RECORD_TOTALI", Integer.class),
            rs.getObject("NUM_RECORD_OK", Integer.class),
            rs.getObject("NUM_RECORD_KO", Integer.class),
            false
    );

    private static final RowMapper<BatchErroreRow> MAPPER_ERRORE = (rs, rowNum) -> new BatchErroreRow(
            rs.getLong("ID_BATCH_ERRORE"),
            rs.getLong("ID_BATCH"),
            rs.getObject("NUMERO_RIGA", Integer.class),
            rs.getString("CHIAVE_BUSINESS"),
            rs.getString("MESSAGGIO_ERRORE"),
            rs.getString("PAYLOAD_JSON"),
            rs.getTimestamp("DT_INSERIMENTO").toInstant()
    );
}
