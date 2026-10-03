package com.example.starter.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 染色批次链路数据访问层：基于 JdbcTemplate，全部使用参数绑定。
 */
@Repository
public class DyeBatchRepository {

    /** 染色批次行。 */
    public record BatchRow(long id, String batchNo, double targetL, double targetA, double targetB,
            int targetVersion, String status, Instant createdAt) {
    }

    /** 布卷行。 */
    public record RollRow(long id, long batchId, String rollNo, Instant createdAt) {
    }

    /** 测色记录行。 */
    public record MeasurementRow(long id, long rollId, String kind, double l, double a, double b,
            Instant measuredAt, int targetVersion, double deltaE, boolean qualified,
            String requestId, String requestHash, Instant createdAt) {
    }

    /** 复检裁决行。 */
    public record RulingRow(long id, long rollId, long recheckId, String decision,
            String inspector, Instant createdAt) {
    }

    /** 放行单行。 */
    public record ReleaseRow(long id, long batchId, String requestId, String requestHash,
            int targetVersion, Instant releasedAt) {
    }

    /** 放行冻结明细行。 */
    public record ReleaseLineRow(long id, long releaseId, long rollId, String rollNo,
            String adoptedKind, double l, double a, double b, Instant measuredAt, double deltaE) {
    }

    /** 勘误行。 */
    public record ErrataRow(long id, long releaseId, String note, Instant createdAt) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_no"), rs.getDouble("target_l"),
            rs.getDouble("target_a"), rs.getDouble("target_b"), rs.getInt("target_version"),
            rs.getString("status"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RollRow> ROLL_MAPPER = (rs, n) -> new RollRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("roll_no"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<MeasurementRow> MEASUREMENT_MAPPER = (rs, n) -> new MeasurementRow(
            rs.getLong("id"), rs.getLong("roll_id"), rs.getString("kind"),
            rs.getDouble("l_value"), rs.getDouble("a_value"), rs.getDouble("b_value"),
            rs.getTimestamp("measured_at").toInstant(), rs.getInt("target_version"),
            rs.getDouble("delta_e"), rs.getBoolean("qualified"),
            rs.getString("request_id"), rs.getString("request_hash"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<RulingRow> RULING_MAPPER = (rs, n) -> new RulingRow(
            rs.getLong("id"), rs.getLong("roll_id"), rs.getLong("recheck_id"),
            rs.getString("decision"), rs.getString("inspector"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("request_id"),
            rs.getString("request_hash"), rs.getInt("target_version"),
            rs.getTimestamp("released_at").toInstant());

    private static final RowMapper<ReleaseLineRow> LINE_MAPPER = (rs, n) -> new ReleaseLineRow(
            rs.getLong("id"), rs.getLong("release_id"), rs.getLong("roll_id"),
            rs.getString("roll_no"), rs.getString("adopted_kind"),
            rs.getDouble("l_value"), rs.getDouble("a_value"), rs.getDouble("b_value"),
            rs.getTimestamp("measured_at").toInstant(), rs.getDouble("delta_e"));

    private static final RowMapper<ErrataRow> ERRATA_MAPPER = (rs, n) -> new ErrataRow(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("note"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public DyeBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertBatch(String batchNo, double l, double a, double b, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dye_batch (batch_no, target_l, target_a, target_b, target_version, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 1, 'OPEN', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setDouble(2, l);
            ps.setDouble(3, a);
            ps.setDouble(4, b);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<BatchRow> findBatch(long id) {
        return queryOne("SELECT * FROM dye_batch WHERE id = ?", BATCH_MAPPER, id);
    }

    /** 行锁读取批次：放行、裁决、测色、改目标值共用，保证串行化。 */
    public Optional<BatchRow> findBatchForUpdate(long id) {
        return queryOne("SELECT * FROM dye_batch WHERE id = ? FOR UPDATE", BATCH_MAPPER, id);
    }

    public void updateBatchTarget(long id, double l, double a, double b, int newVersion) {
        jdbc.update("UPDATE dye_batch SET target_l = ?, target_a = ?, target_b = ?, target_version = ? WHERE id = ?",
                l, a, b, newVersion, id);
    }

    public void markBatchReleased(long id) {
        jdbc.update("UPDATE dye_batch SET status = 'RELEASED' WHERE id = ?", id);
    }

    public int countRolls(long batchId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM batch_roll WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }

    public long insertRoll(long batchId, String rollNo, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_roll (batch_id, roll_no, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, rollNo);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<RollRow> findRoll(long rollId) {
        return queryOne("SELECT * FROM batch_roll WHERE id = ?", ROLL_MAPPER, rollId);
    }

    public List<RollRow> listRolls(long batchId) {
        return jdbc.query("SELECT * FROM batch_roll WHERE batch_id = ? ORDER BY id", ROLL_MAPPER, batchId);
    }
    public Optional<MeasurementRow> findMeasurementByRequestId(String requestId) {
        return queryOne("SELECT * FROM color_measurement WHERE request_id = ?", MEASUREMENT_MAPPER, requestId);
    }

    /** 某卷某类最新一条测色记录（历史全量保留，最新一条为当前有效值）。 */
    public Optional<MeasurementRow> findLatestMeasurement(long rollId, String kind) {
        return queryOne("SELECT * FROM color_measurement WHERE roll_id = ? AND kind = ? ORDER BY id DESC LIMIT 1",
                MEASUREMENT_MAPPER, rollId, kind);
    }

    public long insertMeasurement(long rollId, String kind, double l, double a, double b,
            Instant measuredAt, int targetVersion, double deltaE, boolean qualified,
            String requestId, String requestHash, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO color_measurement (roll_id, kind, l_value, a_value, b_value, measured_at,"
                            + " target_version, delta_e, qualified, request_id, request_hash, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, rollId);
            ps.setString(2, kind);
            ps.setDouble(3, l);
            ps.setDouble(4, a);
            ps.setDouble(5, b);
            ps.setTimestamp(6, Timestamp.from(measuredAt));
            ps.setInt(7, targetVersion);
            ps.setDouble(8, deltaE);
            ps.setBoolean(9, qualified);
            ps.setString(10, requestId);
            ps.setString(11, requestHash);
            ps.setTimestamp(12, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<RulingRow> findRuling(long rollId) {
        return queryOne("SELECT * FROM recheck_ruling WHERE roll_id = ?", RULING_MAPPER, rollId);
    }

    /** 每卷一条当前有效裁决：已存在则更新（仅未放行批次允许）。 */
    public void upsertRuling(long rollId, long recheckId, String decision, String inspector, Instant now) {
        int updated = jdbc.update(
                "UPDATE recheck_ruling SET recheck_id = ?, decision = ?, inspector = ?, created_at = ? WHERE roll_id = ?",
                recheckId, decision, inspector, Timestamp.from(now), rollId);
        if (updated == 0) {
            jdbc.update("INSERT INTO recheck_ruling (roll_id, recheck_id, decision, inspector, created_at)"
                    + " VALUES (?, ?, ?, ?, ?)", rollId, recheckId, decision, inspector, Timestamp.from(now));
        }
    }

    public Optional<ReleaseRow> findReleaseByRequestId(String requestId) {
        return queryOne("SELECT * FROM release_order WHERE request_id = ?", RELEASE_MAPPER, requestId);
    }

    public Optional<ReleaseRow> findReleaseById(long id) {
        return queryOne("SELECT * FROM release_order WHERE id = ?", RELEASE_MAPPER, id);
    }
    public Optional<ReleaseRow> findReleaseByBatchId(long batchId) {
        return queryOne("SELECT * FROM release_order WHERE batch_id = ?", RELEASE_MAPPER, batchId);
    }

    public long insertRelease(long batchId, String requestId, String requestHash,
            int targetVersion, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_order (batch_id, request_id, request_hash, target_version, released_at)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, requestId);
            ps.setString(3, requestHash);
            ps.setInt(4, targetVersion);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void insertReleaseLine(long releaseId, long rollId, String rollNo, String adoptedKind,
            double l, double a, double b, Instant measuredAt, double deltaE) {
        jdbc.update("INSERT INTO release_line (release_id, roll_id, roll_no, adopted_kind,"
                + " l_value, a_value, b_value, measured_at, delta_e) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                releaseId, rollId, rollNo, adoptedKind, l, a, b, Timestamp.from(measuredAt), deltaE);
    }

    public List<ReleaseLineRow> listReleaseLines(long releaseId) {
        return jdbc.query("SELECT * FROM release_line WHERE release_id = ? ORDER BY id", LINE_MAPPER, releaseId);
    }

    public long insertErrata(long releaseId, String note, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_errata (release_id, note, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, releaseId);
            ps.setString(2, note);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<ErrataRow> listErrata(long releaseId) {
        return jdbc.query("SELECT * FROM release_errata WHERE release_id = ? ORDER BY id", ERRATA_MAPPER, releaseId);
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}