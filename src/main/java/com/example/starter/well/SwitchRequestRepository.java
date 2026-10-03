package com.example.starter.well;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 切换申请与审核快照存取。
 */
@Repository
public class SwitchRequestRepository {

    private final JdbcTemplate jdbc;

    public SwitchRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SwitchRequest> MAPPER = (rs, rowNum) -> map(rs);

    private static SwitchRequest map(ResultSet rs) throws SQLException {
        return new SwitchRequest(
                rs.getLong("id"),
                rs.getString("well_no"),
                rs.getString("request_id"),
                rs.getString("payload_hash"),
                rs.getString("from_mode"),
                rs.getString("to_mode"),
                rs.getString("status"),
                rs.getString("reject_reason"),
                SegmentRepository.toInstant(rs, "new_segment_start"),
                SegmentRepository.toInstant(rs, "created_at"),
                SegmentRepository.toInstant(rs, "decided_at"));
    }

    private static final RowMapper<SampleSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) ->
            new SampleSnapshot(rs.getLong("sample_id"), rs.getBigDecimal("pressure_mpa"),
                    SegmentRepository.toInstant(rs, "sampled_at"));

    public Optional<SwitchRequest> findById(long id) {
        List<SwitchRequest> rows = jdbc.query("SELECT * FROM switch_request WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按主键查询并加行锁，审核并发时串行化。 */
    public Optional<SwitchRequest> findByIdForUpdate(long id) {
        List<SwitchRequest> rows = jdbc.query(
                "SELECT * FROM switch_request WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<SwitchRequest> findByRequestId(String wellNo, String requestId) {
        List<SwitchRequest> rows = jdbc.query(
                "SELECT * FROM switch_request WHERE well_no = ? AND request_id = ?",
                MAPPER, wellNo, requestId);
        return rows.stream().findFirst();
    }

    public List<SwitchRequest> findByWell(String wellNo) {
        return jdbc.query("SELECT * FROM switch_request WHERE well_no = ? ORDER BY id", MAPPER, wellNo);
    }

    /** 新建待审核申请，返回主键。 */
    public long insert(String wellNo, String requestId, String payloadHash,
                       String fromMode, String toMode, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO switch_request (well_no, request_id, payload_hash, from_mode, to_mode,"
                            + " status, created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, wellNo);
            ps.setString(2, requestId);
            ps.setString(3, payloadHash);
            ps.setString(4, fromMode);
            ps.setString(5, toMode);
            ps.setObject(6, SegmentRepository.toOffset(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 记录审核决定。 */
    public void decide(long id, String status, String rejectReason, Instant newSegmentStart,
                       Instant decidedAt) {
        jdbc.update("UPDATE switch_request SET status = ?, reject_reason = ?,"
                        + " new_segment_start = ?, decided_at = ? WHERE id = ?",
                status, rejectReason, SegmentRepository.toOffset(newSegmentStart),
                SegmentRepository.toOffset(decidedAt), id);
    }

    /** 冻结审核所用采样快照。 */
    public void insertSnapshot(long switchRequestId, List<PressureSample> samples) {
        jdbc.batchUpdate("INSERT INTO switch_request_sample"
                        + " (switch_request_id, sample_id, pressure_mpa, sampled_at) VALUES (?, ?, ?, ?)",
                samples, samples.size(), (ps, sample) -> {
                    ps.setLong(1, switchRequestId);
                    ps.setLong(2, sample.id());
                    ps.setBigDecimal(3, sample.pressureMpa());
                    ps.setObject(4, SegmentRepository.toOffset(sample.sampledAt()));
                });
    }

    /** 查询审核冻结的采样快照，按采样时刻升序。 */
    public List<SampleSnapshot> findSnapshots(long switchRequestId) {
        return jdbc.query("SELECT * FROM switch_request_sample WHERE switch_request_id = ?"
                + " ORDER BY sampled_at, sample_id", SNAPSHOT_MAPPER, switchRequestId);
    }
}