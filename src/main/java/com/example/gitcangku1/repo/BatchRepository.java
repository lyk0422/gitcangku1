package com.example.gitcangku1.repo;

import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.DyeBatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DyeBatch> MAPPER = (rs, n) -> map(rs);

    private static DyeBatch map(ResultSet rs) throws SQLException {
        return new DyeBatch(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getDouble("target_l"),
                rs.getDouble("target_a"),
                rs.getDouble("target_b"),
                rs.getInt("target_version"),
                rs.getInt("expected_rolls"),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    public DyeBatch insert(String batchNo, double l, double a, double b, int expectedRolls, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO dye_batch(batch_no,target_l,target_a,target_b,target_version,expected_rolls,status,created_at)"
                            + " VALUES(?,?,?,?,1,?,'OPEN',?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setDouble(2, l);
            ps.setDouble(3, a);
            ps.setDouble(4, b);
            ps.setInt(5, expectedRolls);
            ps.setObject(6, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, kh);
        return findById(kh.getKey().longValue()).orElseThrow();
    }

    public Optional<DyeBatch> findById(long id) {
        return jdbc.query("SELECT * FROM dye_batch WHERE id=?", MAPPER, id).stream().findFirst();
    }

    /** 行级悲观锁，串行化测色/裁决/放行等并发事务。 */
    public Optional<DyeBatch> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dye_batch WHERE id=? FOR UPDATE", MAPPER, id).stream().findFirst();
    }

    public void updateTarget(long id, double l, double a, double b, int newVersion) {
        jdbc.update("UPDATE dye_batch SET target_l=?, target_a=?, target_b=?, target_version=? WHERE id=?",
                l, a, b, newVersion, id);
    }

    public void updateStatus(long id, BatchStatus status) {
        jdbc.update("UPDATE dye_batch SET status=? WHERE id=?", status.name(), id);
    }
}