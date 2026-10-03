package com.example.coral.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.coral.domain.Quadrat;
import com.example.coral.domain.QuadratStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 样方持久化：提供行级锁（FOR UPDATE）用于容量、复核与封存的事务裁决。
 */
@Repository
public class QuadratRepository {

    private static final RowMapper<Quadrat> MAPPER = (rs, n) -> new Quadrat(
            rs.getLong("id"),
            rs.getString("quadrat_code"),
            rs.getString("name"),
            QuadratStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sealed_at") == null ? null : rs.getTimestamp("sealed_at").toInstant());

    private final JdbcTemplate jdbc;

    public QuadratRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String quadratCode, String name, Instant createdAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO quadrat (quadrat_code, name, status, created_at) VALUES (?, ?, 'OPEN', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, quadratCode);
            ps.setString(2, name);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Quadrat> findByCode(String quadratCode) {
        return jdbc.query("SELECT * FROM quadrat WHERE quadrat_code = ?", MAPPER, quadratCode)
                .stream().findFirst();
    }

    public Optional<Quadrat> findById(long id) {
        return jdbc.query("SELECT * FROM quadrat WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
    /** 按编号加行锁读取，同事务内串行化容量、复核与封存竞争。 */
    public Optional<Quadrat> lockByCode(String quadratCode) {
        return jdbc.query("SELECT * FROM quadrat WHERE quadrat_code = ? FOR UPDATE", MAPPER, quadratCode)
                .stream().findFirst();
    }

    /** 按主键加行锁读取。 */
    public Optional<Quadrat> lockById(long id) {
        return jdbc.query("SELECT * FROM quadrat WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public void markSealed(long id, Instant sealedAt) {
        jdbc.update("UPDATE quadrat SET status = 'SEALED', sealed_at = ? WHERE id = ?",
                Timestamp.from(sealedAt), id);
    }
}