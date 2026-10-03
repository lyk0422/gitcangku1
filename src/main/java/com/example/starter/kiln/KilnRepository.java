package com.example.starter.kiln;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

/**
 * 窑炉批次数据访问：基于 JdbcTemplate，全部使用参数绑定；
 * 时间列按 UTC 读写（显式 UTC Calendar），与 JVM 默认时区无关。
 */
@Repository
public class KilnRepository {

    /** 程序段行。 */
    public record SegmentRow(long id, long programId, int seq, Instant startUtc, Instant endUtc,
                             double targetTemp, double tolerance) {
    }

    /** 批次行。 */
    public record BatchRow(long id, String batchNo, long programId, String programVersion,
                           String status, Long refireOf) {
    }

    /** 测温点行。 */
    public record PointRow(long id, long batchId, Instant measuredAt, double temperature) {
    }

    /** 异常标记行。 */
    public record AnomalyRow(long id, long batchId, String reason, String markedBy, Instant markedAt) {
    }

    /** 放行记录行。 */
    public record ReleaseRow(long id, long batchId, String decision, String programVersion,
                             int pointsLimit, String deviations, String inspector, Instant decidedAt) {
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column, utc()).toInstant();
    }

    private static final RowMapper<SegmentRow> SEGMENT_MAPPER = (rs, n) -> new SegmentRow(
            rs.getLong("id"), rs.getLong("program_id"), rs.getInt("seq"),
            readInstant(rs, "start_utc"), readInstant(rs, "end_utc"),
            rs.getDouble("target_temp"), rs.getDouble("tolerance"));

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> new BatchRow(
            rs.getLong("id"), rs.getString("batch_no"), rs.getLong("program_id"),
            rs.getString("program_version"), rs.getString("status"),
            rs.getObject("refire_of") == null ? null : rs.getLong("refire_of"));

    private static final RowMapper<PointRow> POINT_MAPPER = (rs, n) -> new PointRow(
            rs.getLong("id"), rs.getLong("batch_id"),
            readInstant(rs, "measured_at"), rs.getDouble("temperature"));

    private static final RowMapper<AnomalyRow> ANOMALY_MAPPER = (rs, n) -> new AnomalyRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("reason"),
            rs.getString("marked_by"), readInstant(rs, "marked_at"));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, n) -> new ReleaseRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("decision"),
            rs.getString("program_version"), rs.getInt("points_limit"), rs.getString("deviations"),
            rs.getString("inspector"), readInstant(rs, "decided_at"));

    private final JdbcTemplate jdbc;

    public KilnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertProgram(String name, String version, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_program (name, version, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, version);
            ps.setTimestamp(3, Timestamp.from(now), utc());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public void insertSegment(long programId, int seq, Instant start, Instant end,
                              double target, double tolerance) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_segment (program_id, seq, start_utc, end_utc, target_temp, tolerance)"
                            + " VALUES (?, ?, ?, ?, ?, ?)");
            ps.setLong(1, programId);
            ps.setInt(2, seq);
            ps.setTimestamp(3, Timestamp.from(start), utc());
            ps.setTimestamp(4, Timestamp.from(end), utc());
            ps.setDouble(5, target);
            ps.setDouble(6, tolerance);
            return ps;
        });
    }

    public Optional<String> findProgramVersion(long programId) {
        List<String> rows = jdbc.query("SELECT version FROM kiln_program WHERE id = ?",
                (rs, n) -> rs.getString(1), programId);
        return rows.stream().findFirst();
    }

    public List<SegmentRow> segmentsOf(long programId) {
        return jdbc.query("SELECT * FROM kiln_segment WHERE program_id = ? ORDER BY seq",
                SEGMENT_MAPPER, programId);
    }

    public long insertBatch(String batchNo, long programId, String programVersion,
                            Long refireOf, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_batch (batch_no, program_id, program_version, status, refire_of, created_at)"
                            + " VALUES (?, ?, ?, 'FIRING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setLong(2, programId);
            ps.setString(3, programVersion);
            if (refireOf == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, refireOf);
            }
            ps.setTimestamp(5, Timestamp.from(now), utc());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public Optional<BatchRow> findBatch(long batchId) {
        return jdbc.query("SELECT * FROM kiln_batch WHERE id = ?", BATCH_MAPPER, batchId)
                .stream().findFirst();
    }

    /** 锁定批次行（SELECT ... FOR UPDATE），用于放行与测温上传的串行化。 */
    public Optional<BatchRow> lockBatch(long batchId) {
        return jdbc.query("SELECT * FROM kiln_batch WHERE id = ? FOR UPDATE", BATCH_MAPPER, batchId)
                .stream().findFirst();
    }

    public void updateBatchStatus(long batchId, String status) {
        jdbc.update("UPDATE kiln_batch SET status = ? WHERE id = ?", status, batchId);
    }

    public int countRefiresOf(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_batch WHERE refire_of = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }

    public List<PointRow> pointsOf(long batchId) {
        return jdbc.query("SELECT * FROM kiln_temp_point WHERE batch_id = ? ORDER BY measured_at",
                POINT_MAPPER, batchId);
    }

    public void insertPoint(long batchId, Instant measuredAt, double temperature,
                            String requestId, Instant now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_temp_point (batch_id, measured_at, temperature, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)");
            ps.setLong(1, batchId);
            ps.setTimestamp(2, Timestamp.from(measuredAt), utc());
            ps.setDouble(3, temperature);
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(now), utc());
            return ps;
        });
    }

    /** 查询已登记的幂等请求，返回 (payloadHash, pointCount)。 */
    public Optional<String[]> findUploadRequest(long batchId, String requestId) {
        List<String[]> rows = jdbc.query(
                "SELECT payload_hash, point_count FROM kiln_upload_request WHERE batch_id = ? AND request_id = ?",
                (rs, n) -> new String[]{rs.getString(1), String.valueOf(rs.getInt(2))},
                batchId, requestId);
        return rows.stream().findFirst();
    }

    public void insertUploadRequest(long batchId, String requestId, String payloadHash,
                                    int pointCount, Instant now) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_upload_request (batch_id, request_id, payload_hash, point_count, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)");
            ps.setLong(1, batchId);
            ps.setString(2, requestId);
            ps.setString(3, payloadHash);
            ps.setInt(4, pointCount);
            ps.setTimestamp(5, Timestamp.from(now), utc());
            return ps;
        });
    }

    public long insertAnomaly(long batchId, String reason, String markedBy, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_anomaly_mark (batch_id, reason, marked_by, marked_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, reason);
            ps.setString(3, markedBy);
            ps.setTimestamp(4, Timestamp.from(now), utc());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public List<AnomalyRow> anomaliesOf(long batchId) {
        return jdbc.query("SELECT * FROM kiln_anomaly_mark WHERE batch_id = ? ORDER BY id",
                ANOMALY_MAPPER, batchId);
    }

    public Optional<ReleaseRow> findRelease(long batchId) {
        return jdbc.query("SELECT * FROM kiln_release_record WHERE batch_id = ?",
                RELEASE_MAPPER, batchId).stream().findFirst();
    }

    public long insertRelease(long batchId, String decision, String programVersion, int pointsLimit,
                              String deviations, String inspector, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_release_record (batch_id, decision, program_version, points_limit,"
                            + " deviations, inspector, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, decision);
            ps.setString(3, programVersion);
            ps.setInt(4, pointsLimit);
            ps.setString(5, deviations);
            ps.setString(6, inspector);
            ps.setTimestamp(7, Timestamp.from(now), utc());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}