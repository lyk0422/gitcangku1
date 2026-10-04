package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 在途异常记录表访问。记录只追加、不可变，不提供任何更新或删除。
 */
@Repository
public class TransferAnomalyRepository {

    private static final AnomalyRowMapper ROW_MAPPER = new AnomalyRowMapper();

    private final JdbcTemplate jdbc;

    public TransferAnomalyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条异常记录（超时或封条不一致），同时由服务层冻结所属交接。
     */
    public void insert(long transferId, String evidenceKey, int checkpointSeq,
                       AnomalyReason reason, String expectedSealNo, String submittedSealNo,
                       LocalDateTime arrivedAtUtc, LocalDateTime deadlineUtc, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transfer_anomaly
                            (transfer_id, evidence_key, checkpoint_seq, reason,
                             expected_seal_no, submitted_seal_no, arrived_at_utc, deadline_utc, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, checkpointSeq, reason.name(),
                expectedSealNo, submittedSealNo, arrivedAtUtc, deadlineUtc, now);
    }

    /**
     * 按发生顺序查询某次交接的全部异常记录。
     */
    public List<TransferAnomaly> findByTransferId(long transferId) {
        return jdbc.query(
                "SELECT * FROM transfer_anomaly WHERE transfer_id = ? ORDER BY id",
                ROW_MAPPER, transferId);
    }

    /**
     * 按发生顺序查询证物全部历史交接的异常记录（含已取消/已冻结交接的旧链）。
     */
    public List<TransferAnomaly> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM transfer_anomaly WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class AnomalyRowMapper implements RowMapper<TransferAnomaly> {
        @Override
        public TransferAnomaly mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferAnomaly(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("checkpoint_seq"),
                    AnomalyReason.valueOf(rs.getString("reason")),
                    rs.getString("expected_seal_no"),
                    rs.getString("submitted_seal_no"),
                    rs.getObject("arrived_at_utc", LocalDateTime.class),
                    rs.getObject("deadline_utc", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}