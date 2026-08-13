package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.VoceDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class VoceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insertVersione;

    public VoceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertVersione = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_VOCE_ST")
                .usingGeneratedKeyColumns("ID_VOCE_ST");
    }

    public Optional<VoceRow> trovaVersioneCorrente(String idVoce) {
        return jdbcTemplate.query("""
                SELECT * FROM T_VOCE_ST WHERE ID_VOCE = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, MAPPER, idVoce).stream().findFirst();
    }

    public List<VoceRow> elencaStorico(String idVoce) {
        return jdbcTemplate.query("SELECT * FROM T_VOCE_ST WHERE ID_VOCE = ? ORDER BY DT_INSERIMENTO", MAPPER, idVoce);
    }

    public List<VoceRow> elencaCorrenti() {
        return jdbcTemplate.query("""
                SELECT * FROM T_VOCE_ST WHERE FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO' ORDER BY ID_VOCE
                """, MAPPER);
    }

    public List<String> trovaChiaviCorrentiPerBatch(long idBatch) {
        return jdbcTemplate.queryForList("""
                SELECT ID_VOCE FROM T_VOCE_ST
                 WHERE ID_BATCH_CARICAMENTO = ? AND FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO'
                """, String.class, idBatch);
    }

    @Transactional
    public void sostituisciVersioneCorrente(VoceDto dto, long idBatch, StatoRecord statoRecord) {
        chiudiVersioneCorrente(dto.idVoce());
        inserisciVersione(dto, idBatch, statoRecord);
    }

    private void chiudiVersioneCorrente(String idVoce) {
        jdbcTemplate.update("""
                UPDATE T_VOCE_ST SET FLAG_ULTIMA_VERSIONE = 'N' WHERE ID_VOCE = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, idVoce);
    }

    private long inserisciVersione(VoceDto dto, long idBatch, StatoRecord statoRecord) {
        Map<String, Object> valori = new HashMap<>();
        valori.put("ID_VOCE", dto.idVoce());
        valori.put("DESCRIZIONE", dto.descrizione());
        valori.put("MESE_RIFERIMENTO_RICHIESTO", dto.meseRiferimentoRichiesto() ? "S" : "N");
        valori.put("ID_BATCH_CARICAMENTO", idBatch);
        valori.put("STATO_RECORD", statoRecord.name());
        // Vedi nota in RiderAnagraficaRepository: SimpleJdbcInsert non rispetta
        // il DEFAULT lato Oracle per queste colonne in questo ambiente.
        valori.put("DT_INSERIMENTO", Timestamp.from(Instant.now()));
        valori.put("FLAG_ULTIMA_VERSIONE", "S");
        return insertVersione.executeAndReturnKey(valori).longValue();
    }

    private static final RowMapper<VoceRow> MAPPER = (rs, rowNum) -> new VoceRow(
            rs.getLong("ID_VOCE_ST"),
            new VoceDto(rs.getString("ID_VOCE"), rs.getString("DESCRIZIONE"),
                    "S".equals(rs.getString("MESE_RIFERIMENTO_RICHIESTO"))),
            rs.getLong("ID_BATCH_CARICAMENTO"),
            rs.getTimestamp("DT_INSERIMENTO").toInstant(),
            StatoRecord.valueOf(rs.getString("STATO_RECORD"))
    );
}
