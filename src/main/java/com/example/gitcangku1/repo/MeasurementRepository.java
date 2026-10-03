package com.example.gitcangku1.repo;

import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.domain.Measurement;
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
public class MeasurementRepository {

    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Measurement> MAPPER = (rs, n) -> map(rs);

    private static Measurement map(ResultSet rs) throws SQLException {
        return new Measurement(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getInt("roll_no"),
                MeasureType.valueOf(rs.getString("measure_type")),
                rs.getDouble("l_value"),
                rs.getDouble("a_value"),
                rs.getDouble("b_value"),
                rs.getObject("measured_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getInt("target_version"),
                rs.getString("request_id"),
                rs.getString("request_hash"));
    }

    public Measurement insert(Measurement m, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO color_measurement(batch_id,roll_no,measure_type,l_value,a_value,b_value,"
                            + "measured_at,target_version,request_id,request_hash,created_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, m.batchId());
            ps.setInt(2, m.rollNo());
            ps.setString(3, m.type().name());
            ps.setDouble(4, m.l());
            ps.setDouble(5, m.a());
            ps.setDouble(6, m.b());
            ps.setObject(7, LocalDateTime.ofInstant(m.measuredAt(), ZoneOffset.UTC));
            ps.setInt(8, m.targetVersion());
            ps.setString(9, m.requestId());
            ps.setString(10, m.requestHash());
            ps.setObject(11, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, kh);
        return findById(kh.getKey().longValue()).orElseThrow();
    }

    public Optional<Measurement> findById(long id) {
        return jdbc.query("SELECT * FROM color_measurement WHERE id=?", MAPPER, id).stream().findFirst();
    }

    public Optional<Measurement> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM color_measurement WHERE request_id=?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<Measurement> find(long batchId, int rollNo, MeasureType type) {
        return jdbc.query("SELECT * FROM color_measurement WHERE batch_id=? AND roll_no=? AND measure_type=? ORDER BY id DESC LIMIT 1",
                MAPPER, batchId, rollNo, type.name()).stream().findFirst();
    }

    public List<Measurement> findByBatch(long batchId) {
        return jdbc.query("SELECT * FROM color_measurement WHERE batch_id=? ORDER BY id",
                MAPPER, batchId);
    }
}