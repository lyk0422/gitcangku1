package com.example.gitcangku1.repo;

import com.example.gitcangku1.domain.Adjudication;
import com.example.gitcangku1.domain.MeasureType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AdjudicationRepository {

    private final JdbcTemplate jdbc;

    public AdjudicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Adjudication> MAPPER = (rs, n) -> map(rs);

    private static Adjudication map(ResultSet rs) throws SQLException {
        return new Adjudication(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getInt("roll_no"),
                MeasureType.valueOf(rs.getString("decision")),
                rs.getString("inspector"),
                rs.getObject("decided_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    public Adjudication insert(long batchId, int rollNo, MeasureType decision, String inspector, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO recheck_adjudication(batch_id,roll_no,decision,inspector,decided_at)"
                            + " VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, rollNo);
            ps.setString(3, decision.name());
            ps.setString(4, inspector);
            ps.setObject(5, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        return jdbc.query("SELECT * FROM recheck_adjudication WHERE id=?", MAPPER, id)
                .stream().findFirst().orElseThrow();
    }

    public Optional<Adjudication> find(long batchId, int rollNo) {
        return jdbc.query("SELECT * FROM recheck_adjudication WHERE batch_id=? AND roll_no=?",
                MAPPER, batchId, rollNo).stream().findFirst();
    }

    public List<Adjudication> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM recheck_adjudication WHERE batch_id=? ORDER BY roll_no",
                MAPPER, batchId);
    }
}