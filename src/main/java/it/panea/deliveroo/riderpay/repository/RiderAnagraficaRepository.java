package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.RiderAnagraficaDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class RiderAnagraficaRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insertVersione;

    public RiderAnagraficaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertVersione = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_RIDER_ANAGRAFICA_ST")
                .usingGeneratedKeyColumns("ID_ANAGRAFICA_ST");
    }

    public Optional<RiderAnagraficaRow> trovaVersioneCorrente(String idRider) {
        return jdbcTemplate.query("""
                SELECT * FROM T_RIDER_ANAGRAFICA_ST WHERE ID_RIDER = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, MAPPER, idRider).stream().findFirst();
    }

    public List<RiderAnagraficaRow> elencaStorico(String idRider) {
        return jdbcTemplate.query("""
                SELECT * FROM T_RIDER_ANAGRAFICA_ST WHERE ID_RIDER = ? ORDER BY DT_INSERIMENTO
                """, MAPPER, idRider);
    }

    /** Usato da DELETE /batch/{id} (§ 9.3) per trovare cosa annullare in blocco. */
    public List<String> trovaChiaviCorrentiPerBatch(long idBatch) {
        return jdbcTemplate.queryForList("""
                SELECT ID_RIDER FROM T_RIDER_ANAGRAFICA_ST
                 WHERE ID_BATCH_CARICAMENTO = ? AND FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO'
                """, String.class, idBatch);
    }

    /**
     * Chiude la versione corrente (se esiste: 0 righe altrimenti, no-op) e inserisce
     * la nuova, in un'unica transazione. Vale sia per un arrivo normale (§ 9.1,
     * statoRecord=ATTIVO) sia per un annullamento (§ 9.3, statoRecord=ANNULLATO).
     * L'indice UX_ANAG_CORRENTE (§ 12.1) rende visibile un eventuale conflitto di
     * concorrenza come DataIntegrityViolationException.
     */
    @Transactional
    public void sostituisciVersioneCorrente(RiderAnagraficaDto dto, long idBatch, StatoRecord statoRecord) {
        chiudiVersioneCorrente(dto.idRider());
        inserisciVersione(dto, idBatch, statoRecord);
    }

    private void chiudiVersioneCorrente(String idRider) {
        jdbcTemplate.update("""
                UPDATE T_RIDER_ANAGRAFICA_ST SET FLAG_ULTIMA_VERSIONE = 'N'
                 WHERE ID_RIDER = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, idRider);
    }

    private long inserisciVersione(RiderAnagraficaDto dto, long idBatch, StatoRecord statoRecord) {
        Map<String, Object> valori = new HashMap<>();
        valori.put("ID_RIDER", dto.idRider());
        valori.put("REGIME_FISCALE", dto.regimeFiscale());
        valori.put("DATA_INIZIO_VALIDITA", Date.valueOf(dto.dataInizio()));
        valori.put("DATA_FINE_VALIDITA", dto.dataFine() != null ? Date.valueOf(dto.dataFine()) : null);
        valori.put("NOME", dto.nome());
        valori.put("COGNOME", dto.cognome());
        valori.put("CODICE_FISCALE", dto.codiceFiscale());
        valori.put("PARTITA_IVA", dto.partitaIva());
        valori.put("TELEFONO_CELLULARE", dto.telefonoCellulare());
        valori.put("EMAIL", dto.email());
        valori.put("INDIRIZZO_RESIDENZA", dto.indirizzoResidenza());
        valori.put("CODICE_ISTAT_RESIDENZA", dto.codiceIstatResidenza());
        valori.put("COMUNE_RESIDENZA", dto.comuneResidenza());
        valori.put("PROVINCIA_RESIDENZA", dto.provinciaResidenza());
        valori.put("CAP_RESIDENZA", dto.capResidenza());
        valori.put("ID_BATCH_CARICAMENTO", idBatch);
        valori.put("STATO_RECORD", statoRecord.name());
        // DT_INSERIMENTO e FLAG_ULTIMA_VERSIONE hanno un DEFAULT lato Oracle, ma
        // SimpleJdbcInsert (in questo ambiente) le tratta comunque come colonne
        // richieste nell'INSERT generato, inviando NULL se non presenti nella mappa:
        // vanno quindi impostate esplicitamente, senza fare affidamento sul DEFAULT.
        valori.put("DT_INSERIMENTO", Timestamp.from(Instant.now()));
        valori.put("FLAG_ULTIMA_VERSIONE", "S");
        return insertVersione.executeAndReturnKey(valori).longValue();
    }

    private static final RowMapper<RiderAnagraficaRow> MAPPER = (rs, rowNum) -> new RiderAnagraficaRow(
            rs.getLong("ID_ANAGRAFICA_ST"),
            new RiderAnagraficaDto(
                    rs.getString("ID_RIDER"),
                    rs.getString("REGIME_FISCALE"),
                    rs.getDate("DATA_INIZIO_VALIDITA").toLocalDate(),
                    rs.getDate("DATA_FINE_VALIDITA") != null ? rs.getDate("DATA_FINE_VALIDITA").toLocalDate() : null,
                    rs.getString("NOME"),
                    rs.getString("COGNOME"),
                    rs.getString("CODICE_FISCALE"),
                    rs.getString("PARTITA_IVA"),
                    rs.getString("TELEFONO_CELLULARE"),
                    rs.getString("EMAIL"),
                    rs.getString("INDIRIZZO_RESIDENZA"),
                    rs.getString("CODICE_ISTAT_RESIDENZA"),
                    rs.getString("COMUNE_RESIDENZA"),
                    rs.getString("PROVINCIA_RESIDENZA"),
                    rs.getString("CAP_RESIDENZA")
            ),
            rs.getLong("ID_BATCH_CARICAMENTO"),
            rs.getTimestamp("DT_INSERIMENTO").toInstant(),
            StatoRecord.valueOf(rs.getString("STATO_RECORD"))
    );
}
