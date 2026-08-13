package it.panea.deliveroo.riders2elh.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MasterKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public MasterKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assicuraRider(String idRider) {
        merge("T_RIDER", "ID_RIDER", idRider);
    }

    public void assicuraVoce(String idVoce) {
        merge("T_VOCE", "ID_VOCE", idVoce);
    }

    // tabella/colonna sono sempre letterali fissi passati dal codice qui sopra,
    // mai valore utente: nessun rischio di SQL injection nella formatted string.
    private void merge(String tabella, String colonna, String valoreChiave) {
        jdbcTemplate.update("""
                MERGE INTO %s t USING (SELECT ? AS %s FROM dual) s
                   ON (t.%s = s.%s)
                 WHEN NOT MATCHED THEN INSERT (%s) VALUES (s.%s)
                """.formatted(tabella, colonna, colonna, colonna, colonna, colonna), valoreChiave);
    }
}
