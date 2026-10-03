package com.example.starter.repo;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.Stratum;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 遗址层位仓储：按遗址编号+层位号唯一定位。
 */
@Repository
public class StratumRepository {

    private static final RowMapper<Stratum> MAPPER = (rs, n) -> new Stratum(
            rs.getLong("id"), rs.getString("site_code"), rs.getString("stratum_no"),
            rs.getString("description"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public StratumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String siteCode, String stratumNo, String description, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO stratum (site_code, stratum_no, description, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, siteCode);
            ps.setString(2, stratumNo);
            ps.setString(3, description);
            ps.setTimestamp(4, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Stratum> findByKey(String siteCode, String stratumNo) {
        return jdbc.query("SELECT * FROM stratum WHERE site_code = ? AND stratum_no = ?",
                MAPPER, siteCode, stratumNo).stream().findFirst();
    }

    public Optional<Stratum> findById(long id) {
        return jdbc.query("SELECT * FROM stratum WHERE id = ?", MAPPER, id).stream().findFirst();
    }
}