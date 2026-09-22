package com.example.starter.db;

import java.sql.PreparedStatement;
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

/** 揭盲申请数据访问。 */
@Repository
public class UnblindRequestRepository {

    private static final String COLUMNS =
            "id, experiment_id, allocation_id, participant_id, applicant_id, reason, status, "
                    + "approver_id, created_at, approved_at";

    private static final RowMapper<UnblindRequestRow> MAPPER = (rs, rowNum) -> new UnblindRequestRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getLong("allocation_id"),
            rs.getString("participant_id"),
            rs.getString("applicant_id"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("approver_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant());

    private final JdbcTemplate jdbc;

    public UnblindRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询某一分配的揭盲申请（任意状态）。 */
    public Optional<UnblindRequestRow> findByAllocation(long allocationId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM unblind_request WHERE allocation_id = ?",
                        MAPPER, allocationId)
                .stream().findFirst();
    }

    /** 按主键查询申请。 */
    public Optional<UnblindRequestRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM unblind_request WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按实验与参与者查询申请。 */
    public List<UnblindRequestRow> findByExperimentAndParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM unblind_request "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY id ASC",
                MAPPER, experimentId, participantId);
    }

    /** 插入待审申请并回填主键。 */
    public long insert(UnblindRequestRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO unblind_request (experiment_id, allocation_id, participant_id, "
                            + "applicant_id, reason, status, approver_id, created_at, approved_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setLong(2, row.allocationId());
            ps.setString(3, row.participantId());
            ps.setString(4, row.applicantId());
            ps.setString(5, row.reason());
            ps.setString(6, row.status());
            if (row.approverId() == null) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, row.approverId());
            }
            ps.setTimestamp(8, Timestamp.from(row.createdAt()));
            if (row.approvedAt() == null) {
                ps.setNull(9, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(9, Timestamp.from(row.approvedAt()));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("unblind request insert did not return generated key");
        }
        return key.longValue();
    }

    /** 仅在 PENDING 状态下批准，返回受影响行数。 */
    public int approveIfPending(long id, String approverId, Instant approvedAt) {
        return jdbc.update(
                "UPDATE unblind_request SET status = 'APPROVED', approver_id = ?, approved_at = ? "
                        + "WHERE id = ? AND status = 'PENDING'",
                approverId, Timestamp.from(approvedAt), id);
    }
}
