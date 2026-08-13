package it.panea.deliveroo.riders2elh.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class ClientOAuthRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClientOAuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ClientOAuthRow> trovaPerClientId(String clientId) {
        return jdbcTemplate.query("""
                SELECT * FROM T_CLIENT_OAUTH WHERE CLIENT_ID = ?
                """, MAPPER, clientId).stream().findFirst();
    }

    public void aggiornaUltimoUtilizzo(String clientId) {
        jdbcTemplate.update("""
                UPDATE T_CLIENT_OAUTH SET DT_ULTIMO_UTILIZZO = ? WHERE CLIENT_ID = ?
                """, Timestamp.from(Instant.now()), clientId);
    }

    private static final RowMapper<ClientOAuthRow> MAPPER = (rs, rowNum) -> new ClientOAuthRow(
            rs.getLong("ID_CLIENT_OAUTH"),
            rs.getString("CLIENT_ID"),
            rs.getString("CLIENT_SECRET_HASH"),
            rs.getString("SCOPE_CONCESSI"),
            rs.getLong("TOKEN_TTL_SECONDI"),
            "S".equals(rs.getString("FLAG_ATTIVO"))
    );
}
