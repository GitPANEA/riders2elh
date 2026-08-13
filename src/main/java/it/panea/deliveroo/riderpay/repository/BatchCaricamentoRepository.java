package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.EsitoBatch;
import it.panea.deliveroo.riderpay.common.TipoEntita;
import it.panea.deliveroo.riderpay.common.TipoOperazione;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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

    public void registraErrore(long idBatch, String chiaveBusiness, String messaggioErrore, String payloadJson) {
        jdbcTemplate.update("""
                INSERT INTO T_BATCH_CARICAMENTO_ERRORE (ID_BATCH, CHIAVE_BUSINESS, MESSAGGIO_ERRORE, PAYLOAD_JSON)
                VALUES (?, ?, ?, ?)
                """, idBatch, chiaveBusiness, messaggioErrore, payloadJson);
    }

    public Optional<BatchRow> trovaPerId(long idBatch) {
        return jdbcTemplate.query("SELECT * FROM T_BATCH_CARICAMENTO WHERE ID_BATCH = ?", MAPPER, idBatch)
                .stream().findFirst();
    }

    /** GET /api/v1/batch?tipoOperazione=... — null = nessun filtro. */
    public List<BatchRow> elenca(TipoOperazione tipoOperazione) {
        if (tipoOperazione == null) {
            return jdbcTemplate.query("SELECT * FROM T_BATCH_CARICAMENTO ORDER BY DT_RICEZIONE DESC", MAPPER);
        }
        return jdbcTemplate.query(
                "SELECT * FROM T_BATCH_CARICAMENTO WHERE TIPO_OPERAZIONE = ? ORDER BY DT_RICEZIONE DESC",
                MAPPER, tipoOperazione.name());
    }

    private static final RowMapper<BatchRow> MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("ID_BATCH"),
            TipoEntita.valueOf(rs.getString("TIPO_ENTITA")),
            TipoOperazione.valueOf(rs.getString("TIPO_OPERAZIONE")),
            (Long) rs.getObject("ID_BATCH_RIFERIMENTO"),
            rs.getString("MOTIVO_OPERAZIONE"),
            rs.getString("NOME_FILE_ORIGINE"),
            rs.getString("CLIENT_ID"),
            rs.getTimestamp("DT_RICEZIONE").toInstant(),
            rs.getTimestamp("DT_FINE_ELABORAZIONE") != null ? rs.getTimestamp("DT_FINE_ELABORAZIONE").toInstant() : null,
            rs.getString("ESITO") != null ? EsitoBatch.valueOf(rs.getString("ESITO")) : null,
            (Integer) rs.getObject("NUM_RECORD_TOTALI"),
            (Integer) rs.getObject("NUM_RECORD_OK"),
            (Integer) rs.getObject("NUM_RECORD_KO")
    );
}
