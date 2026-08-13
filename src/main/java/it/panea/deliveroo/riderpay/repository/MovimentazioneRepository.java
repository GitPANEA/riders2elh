package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.common.TipoSezione;
import it.panea.deliveroo.riderpay.dto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MovimentazioneRepository {

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insertHeader;
    private final SimpleJdbcInsert insertConsegna;
    private final SimpleJdbcInsert insertVoce;

    public MovimentazioneRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertHeader = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_MOVIMENTAZIONE_ST").usingGeneratedKeyColumns("ID_MOVIMENTAZIONE_ST");
        // usingGeneratedKeyColumns e necessario anche qui, pur non usando la chiave
        // restituita: senza, SimpleJdbcInsert include la colonna IDENTITY tra quelle
        // dell'INSERT e Oracle rifiuta con ORA-32795 (cannot insert into a generated
        // always identity column).
        this.insertConsegna = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_MOVIMENTAZIONE_CONSEGNA_ST").usingGeneratedKeyColumns("ID_CONSEGNA_ST");
        this.insertVoce = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("T_MOVIMENTAZIONE_VOCE_ST").usingGeneratedKeyColumns("ID_MOV_VOCE_ST");
    }

    public Optional<MovimentazioneRow> trovaVersioneCorrente(String idMovimentazione) {
        return jdbcTemplate.query("""
                SELECT ID_MOVIMENTAZIONE_ST FROM T_MOVIMENTAZIONE_ST
                 WHERE ID_MOVIMENTAZIONE = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, (rs, n) -> rs.getLong(1), idMovimentazione)
                .stream().findFirst()
                .map(this::costruisciRigaCompleta);
    }

    /** § 10: storico completo di tutte le versioni ricevute per una movimentazione. */
    public List<MovimentazioneVersioneSintesi> elencaStorico(String idMovimentazione) {
        return jdbcTemplate.query("""
                SELECT ID_MOVIMENTAZIONE_ST, DT_INSERIMENTO, TOTALE_DOVUTO, ID_BATCH_CARICAMENTO, STATO_RECORD
                  FROM T_MOVIMENTAZIONE_ST
                 WHERE ID_MOVIMENTAZIONE = ?
                 ORDER BY DT_INSERIMENTO
                """, (rs, n) -> new MovimentazioneVersioneSintesi(
                        rs.getLong("ID_MOVIMENTAZIONE_ST"),
                        rs.getTimestamp("DT_INSERIMENTO").toInstant(),
                        rs.getBigDecimal("TOTALE_DOVUTO"),
                        rs.getLong("ID_BATCH_CARICAMENTO"),
                        StatoRecord.valueOf(rs.getString("STATO_RECORD"))
                ), idMovimentazione);
    }

    public List<MovimentazioneHeaderRow> elencaCorrentiPerRider(String idRider, LocalDate periodoDa, LocalDate periodoA) {
        return jdbcTemplate.query("""
                SELECT * FROM T_MOVIMENTAZIONE_ST
                 WHERE ID_RIDER = ? AND FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO'
                   AND (? IS NULL OR PERIODO_DA >= ?)
                   AND (? IS NULL OR PERIODO_A <= ?)
                 ORDER BY PERIODO_DA DESC
                """, HEADER_MAPPER, idRider,
                periodoDa, periodoDa != null ? Date.valueOf(periodoDa) : null,
                periodoA, periodoA != null ? Date.valueOf(periodoA) : null);
    }

    public List<String> trovaChiaviCorrentiPerBatch(long idBatch) {
        return jdbcTemplate.queryForList("""
                SELECT ID_MOVIMENTAZIONE FROM T_MOVIMENTAZIONE_ST
                 WHERE ID_BATCH_CARICAMENTO = ? AND FLAG_ULTIMA_VERSIONE = 'S' AND STATO_RECORD = 'ATTIVO'
                """, String.class, idBatch);
    }

    /**
     * Chiude la versione corrente e inserisce la nuova (header + dettaglio),
     * in un'unica transazione — vedi la stessa nota su RiderAnagraficaRepository.
     * Se statoRecord=ANNULLATO non vengono scritte righe di dettaglio (§ 7.3).
     */
    @Transactional
    public long sostituisciVersioneCorrente(MovimentazioneDto dto, long idBatch, StatoRecord statoRecord) {
        chiudiVersioneCorrente(dto.idMovimentazione());
        long idMovimentazioneSt = inserisciHeader(dto, idBatch, statoRecord);
        if (statoRecord == StatoRecord.ATTIVO) {
            inserisciConsegne(idMovimentazioneSt, dto.consegne());
            inserisciVociSezione(idMovimentazioneSt, TipoSezione.MODIFICA_INTEGRAZIONE, dto.modificheIntegrazioni());
            inserisciVociSezione(idMovimentazioneSt, TipoSezione.PROSPETTO_FINALE, dto.prospettoFinale());
        }
        return idMovimentazioneSt;
    }

    private void chiudiVersioneCorrente(String idMovimentazione) {
        jdbcTemplate.update("""
                UPDATE T_MOVIMENTAZIONE_ST SET FLAG_ULTIMA_VERSIONE = 'N'
                 WHERE ID_MOVIMENTAZIONE = ? AND FLAG_ULTIMA_VERSIONE = 'S'
                """, idMovimentazione);
    }

    private long inserisciHeader(MovimentazioneDto dto, long idBatch, StatoRecord statoRecord) {
        TotaliVoceDto tm = dto.totaliModificheIntegrazioni();
        RiepilogoDto r = dto.riepilogo();
        Map<String, Object> valori = new HashMap<>();
        valori.put("ID_MOVIMENTAZIONE", dto.idMovimentazione());
        valori.put("ID_RIDER", dto.idRider());
        valori.put("PERIODO_DA", Date.valueOf(dto.periodoDa()));
        valori.put("PERIODO_A", Date.valueOf(dto.periodoA()));
        valori.put("TOT_NUMERO_CONSEGNE", dto.totaliConsegne() != null ? dto.totaliConsegne().numeroConsegne() : null);
        valori.put("TOT_CONSEGNE_LORDO", dto.totaliConsegne() != null ? dto.totaliConsegne().totaleParzialeLordo() : null);
        valori.put("TOT_MODIFICHE_IMPORTO_LORDO", tm != null ? tm.importoLordo() : null);
        valori.put("TOT_MODIFICHE_RITENUTA_PERC", tm != null ? tm.ritenutaPercentuale() : null);
        valori.put("TOT_MODIFICHE_RITENUTA_IMPORTO", tm != null ? tm.ritenutaImporto() : null);
        valori.put("TOT_MODIFICHE_IVA_PERC", tm != null ? tm.ivaPercentuale() : null);
        valori.put("TOT_MODIFICHE_IVA_IMPORTO", tm != null ? tm.ivaImporto() : null);
        valori.put("TOT_MODIFICHE_TOTALE", tm != null ? tm.totale() : null);
        valori.put("IMPOSTA_BOLLO", r.impostaBollo());
        valori.put("PERC_TRATTENUTE_FISCALI", r.percentualeTrattenuteFiscali());
        valori.put("IMPORTO_TRATTENUTE_FISCALI", r.importoTrattenuteFiscali());
        valori.put("PERC_TRATTENUTE_PREVIDENZIALI", r.percentualeTrattenutePrevidenziali());
        valori.put("IMPORTO_TRATTENUTE_PREVIDENZIALI", r.importoTrattenutePrevidenziali());
        valori.put("PAGAMENTI_CONTANTI_GIA_RISCOSSI", r.pagamentiContantiGiaRiscossi());
        valori.put("TOTALE_DOVUTO", r.totaleDovuto());
        valori.put("ID_BATCH_CARICAMENTO", idBatch);
        valori.put("STATO_RECORD", statoRecord.name());
        // Vedi nota in RiderAnagraficaRepository: SimpleJdbcInsert non rispetta
        // il DEFAULT lato Oracle per queste colonne in questo ambiente.
        valori.put("DT_INSERIMENTO", Timestamp.from(Instant.now()));
        valori.put("FLAG_ULTIMA_VERSIONE", "S");
        return insertHeader.executeAndReturnKey(valori).longValue();
    }

    private void inserisciConsegne(long idMovimentazioneSt, List<ConsegnaDto> consegne) {
        for (ConsegnaDto c : consegne) {
            insertConsegna.execute(Map.of(
                    "ID_MOVIMENTAZIONE_ST", idMovimentazioneSt,
                    "DATA_CONSEGNA", Date.valueOf(c.data()),
                    "NUMERO_CONSEGNE", c.numeroConsegne(),
                    "TOTALE_PARZIALE_LORDO", c.totaleParzialeLordo()));
        }
    }

    private void inserisciVociSezione(long idMovimentazioneSt, TipoSezione sezione, List<VoceMovimentazioneDto> voci) {
        for (VoceMovimentazioneDto v : voci) {
            Map<String, Object> valori = new HashMap<>();
            valori.put("ID_MOVIMENTAZIONE_ST", idMovimentazioneSt);
            valori.put("TIPO_SEZIONE", sezione.name());
            valori.put("ID_VOCE", v.idVoce());
            valori.put("MESE_RIFERIMENTO", v.meseRiferimento());
            valori.put("IMPORTO_LORDO", v.importoLordo());
            valori.put("RITENUTA_PERCENTUALE", v.ritenutaPercentuale());
            valori.put("RITENUTA_IMPORTO", v.ritenutaImporto());
            valori.put("IVA_PERCENTUALE", v.ivaPercentuale());
            valori.put("IVA_IMPORTO", v.ivaImporto());
            valori.put("TOTALE", v.totale());
            insertVoce.execute(valori);
        }
    }

    private MovimentazioneRow costruisciRigaCompleta(long idMovimentazioneSt) {
        MovimentazioneHeaderRow header = jdbcTemplate.queryForObject(
                "SELECT * FROM T_MOVIMENTAZIONE_ST WHERE ID_MOVIMENTAZIONE_ST = ?", HEADER_MAPPER, idMovimentazioneSt);

        List<ConsegnaDto> consegne = jdbcTemplate.query("""
                SELECT DATA_CONSEGNA, NUMERO_CONSEGNE, TOTALE_PARZIALE_LORDO
                  FROM T_MOVIMENTAZIONE_CONSEGNA_ST WHERE ID_MOVIMENTAZIONE_ST = ? ORDER BY DATA_CONSEGNA
                """, (rs, n) -> new ConsegnaDto(rs.getDate("DATA_CONSEGNA").toLocalDate(),
                        rs.getInt("NUMERO_CONSEGNE"), rs.getBigDecimal("TOTALE_PARZIALE_LORDO")), idMovimentazioneSt);

        MovimentazioneDto dto = new MovimentazioneDto(
                header.idMovimentazione(), header.idRider(), header.periodoDa(), header.periodoA(),
                consegne, header.totaliConsegne(),
                elencaVociSezione(idMovimentazioneSt, TipoSezione.MODIFICA_INTEGRAZIONE),
                header.totaliModificheIntegrazioni(),
                elencaVociSezione(idMovimentazioneSt, TipoSezione.PROSPETTO_FINALE),
                header.riepilogo());

        return new MovimentazioneRow(idMovimentazioneSt, dto, header.idBatchCaricamento(),
                header.dtInserimento(), header.statoRecord());
    }

    private List<VoceMovimentazioneDto> elencaVociSezione(long idMovimentazioneSt, TipoSezione sezione) {
        return jdbcTemplate.query("""
                SELECT ID_VOCE, MESE_RIFERIMENTO, IMPORTO_LORDO, RITENUTA_PERCENTUALE, RITENUTA_IMPORTO,
                       IVA_PERCENTUALE, IVA_IMPORTO, TOTALE
                  FROM T_MOVIMENTAZIONE_VOCE_ST
                 WHERE ID_MOVIMENTAZIONE_ST = ? AND TIPO_SEZIONE = ?
                """, (rs, n) -> new VoceMovimentazioneDto(
                        rs.getString("ID_VOCE"), rs.getString("MESE_RIFERIMENTO"),
                        rs.getBigDecimal("IMPORTO_LORDO"), rs.getBigDecimal("RITENUTA_PERCENTUALE"),
                        rs.getBigDecimal("RITENUTA_IMPORTO"), rs.getBigDecimal("IVA_PERCENTUALE"),
                        rs.getBigDecimal("IVA_IMPORTO"), rs.getBigDecimal("TOTALE")
                ), idMovimentazioneSt, sezione.name());
    }

    private static final RowMapper<MovimentazioneHeaderRow> HEADER_MAPPER = (rs, n) -> new MovimentazioneHeaderRow(
            rs.getLong("ID_MOVIMENTAZIONE_ST"),
            rs.getString("ID_MOVIMENTAZIONE"),
            rs.getString("ID_RIDER"),
            rs.getDate("PERIODO_DA").toLocalDate(),
            rs.getDate("PERIODO_A").toLocalDate(),
            new TotaliConsegneDto(rs.getInt("TOT_NUMERO_CONSEGNE"), rs.getBigDecimal("TOT_CONSEGNE_LORDO")),
            new TotaliVoceDto(rs.getBigDecimal("TOT_MODIFICHE_IMPORTO_LORDO"), rs.getBigDecimal("TOT_MODIFICHE_RITENUTA_PERC"),
                    rs.getBigDecimal("TOT_MODIFICHE_RITENUTA_IMPORTO"), rs.getBigDecimal("TOT_MODIFICHE_IVA_PERC"),
                    rs.getBigDecimal("TOT_MODIFICHE_IVA_IMPORTO"), rs.getBigDecimal("TOT_MODIFICHE_TOTALE")),
            new RiepilogoDto(rs.getBigDecimal("IMPOSTA_BOLLO"), rs.getBigDecimal("PERC_TRATTENUTE_FISCALI"),
                    rs.getBigDecimal("IMPORTO_TRATTENUTE_FISCALI"), rs.getBigDecimal("PERC_TRATTENUTE_PREVIDENZIALI"),
                    rs.getBigDecimal("IMPORTO_TRATTENUTE_PREVIDENZIALI"), rs.getBigDecimal("PAGAMENTI_CONTANTI_GIA_RISCOSSI"),
                    rs.getBigDecimal("TOTALE_DOVUTO")),
            rs.getLong("ID_BATCH_CARICAMENTO"),
            rs.getTimestamp("DT_INSERIMENTO").toInstant(),
            StatoRecord.valueOf(rs.getString("STATO_RECORD"))
    );
}
