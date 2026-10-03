package com.example.starter.repo;

import com.example.starter.domain.PressureSample;
import com.example.starter.domain.RunMode;
import com.example.starter.domain.SwitchRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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

    private static final RowMapper<SwitchRequest> MAPPER = SwitchRequestRepository::map;

    private final JdbcTemplate jdbc;

    public SwitchRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static SwitchRequest map(ResultSet rs, int rowNum) throws SQLException {
        SwitchRequest request = new SwitchRequest();
        request.setId(rs.getLong("id"));
        request.setWellNo(rs.getString("well_no"));
        request.setFromSegmentId(rs.getLong("from_segment_id"));
        request.setTargetMode(RunMode.valueOf(rs.getString("target_mode")));
        request.setStatus(rs.getString("status"));
        request.setRejectReason(rs.getString("reject_reason"));
        long newSegmentId = rs.getLong("new_segment_id");
        request.setNewSegmentId(rs.wasNull() ? null : newSegmentId);
        Timestamp newModeStart = rs.getTimestamp("new_mode_start");
        request.setNewModeStart(newModeStart == null ? null : newModeStart.toInstant());
        request.setDecisionOutcome(rs.getString("decision_outcome"));
        request.setRequestId(rs.getString("request_id"));
        request.setDecisionRequestId(rs.getString("decision_request_id"));
        request.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        request.setDecidedAt(decidedAt == null ? null : decidedAt.toInstant());
        return request;
    }

    /** 插入切换申请并回填主键。 */
    public SwitchRequest insert(SwitchRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO switch_request (well_no, from_segment_id, target_mode, status, reject_reason,"
                            + " decision_outcome, request_id, created_at, decided_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.getWellNo());
            ps.setLong(2, request.getFromSegmentId());
            ps.setString(3, request.getTargetMode().name());
            ps.setString(4, request.getStatus());
            ps.setString(5, request.getRejectReason());
            ps.setString(6, request.getDecisionOutcome());
            ps.setString(7, request.getRequestId());
            ps.setTimestamp(8, Timestamp.from(request.getCreatedAt()));
            ps.setTimestamp(9, request.getDecidedAt() == null ? null : Timestamp.from(request.getDecidedAt()));
            return ps;
        }, keyHolder);
        request.setId(keyHolder.getKey().longValue());
        return request;
    }

    /** 按主键查询申请。 */
    public Optional<SwitchRequest> findById(long id) {
        List<SwitchRequest> rows = jdbc.query("SELECT * FROM switch_request WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 查询井当前的待审核申请。 */
    public Optional<SwitchRequest> findPendingByWell(String wellNo) {
        List<SwitchRequest> rows = jdbc.query(
                "SELECT * FROM switch_request WHERE well_no = ? AND status = 'PENDING'", MAPPER, wellNo);
        return rows.stream().findFirst();
    }

    /** 按申请时刻升序查询井的全部申请。 */
    public List<SwitchRequest> findByWell(String wellNo) {
        return jdbc.query(
                "SELECT * FROM switch_request WHERE well_no = ? ORDER BY created_at, id", MAPPER, wellNo);
    }

    /** 写入审核决定结果。 */
    public void updateDecision(SwitchRequest request) {
        jdbc.update(
                "UPDATE switch_request SET status = ?, reject_reason = ?, new_segment_id = ?, new_mode_start = ?,"
                        + " decision_outcome = ?, decision_request_id = ?, decided_at = ? WHERE id = ?",
                request.getStatus(),
                request.getRejectReason(),
                request.getNewSegmentId(),
                request.getNewModeStart() == null ? null : Timestamp.from(request.getNewModeStart()),
                request.getDecisionOutcome(),
                request.getDecisionRequestId(),
                request.getDecidedAt() == null ? null : Timestamp.from(request.getDecidedAt()),
                request.getId());
    }

    /** 冻结审核所用采样快照。 */
    public void insertFrozenSamples(long switchRequestId, List<Long> sampleIds) {
        for (int i = 0; i < sampleIds.size(); i++) {
            jdbc.update(
                    "INSERT INTO switch_request_sample (switch_request_id, sample_id, seq) VALUES (?,?,?)",
                    switchRequestId, sampleIds.get(i), i + 1);
        }
    }

    /** 读取审核冻结的采样快照（按快照序号升序）。 */
    public List<PressureSample> findFrozenSamples(long switchRequestId) {
        return jdbc.query(
                "SELECT s.* FROM pressure_sample s"
                        + " JOIN switch_request_sample x ON x.sample_id = s.id"
                        + " WHERE x.switch_request_id = ? ORDER BY x.seq",
                (rs, rowNum) -> {
                    PressureSample sample = new PressureSample();
                    sample.setId(rs.getLong("id"));
                    sample.setWellNo(rs.getString("well_no"));
                    sample.setSegmentId(rs.getLong("segment_id"));
                    sample.setPressureMpa(rs.getBigDecimal("pressure_mpa"));
                    sample.setSampledAt(rs.getTimestamp("sampled_at").toInstant());
                    sample.setRequestId(rs.getString("request_id"));
                    sample.setVoided(rs.getBoolean("voided"));
                    sample.setVoidReason(rs.getString("void_reason"));
                    Timestamp voidedAt = rs.getTimestamp("voided_at");
                    sample.setVoidedAt(voidedAt == null ? null : voidedAt.toInstant());
                    long replacedBy = rs.getLong("replaced_by_id");
                    sample.setReplacedById(rs.wasNull() ? null : replacedBy);
                    return sample;
                },
                switchRequestId);
    }
}