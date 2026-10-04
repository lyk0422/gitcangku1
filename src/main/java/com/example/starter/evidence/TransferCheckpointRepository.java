package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 受监管交接检查点表访问。名单在发起时一次性写入，创建后不提供任何更新或删除。
 */
@Repository
public class TransferCheckpointRepository {

    private static final CheckpointRowMapper ROW_MAPPER = new CheckpointRowMapper();

    private final JdbcTemplate jdbc;

    public TransferCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个检查点；(transfer_id, seq) 唯一约束保证名单不重不漏。
     */
    public void insert(long transferId, String evidenceKey, int seq,
                       LocalDateTime deadlineUtc, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transfer_checkpoint
                            (transfer_id, evidence_key, seq, deadline_utc, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                transferId, evidenceKey, seq, deadlineUtc, now);
    }

    /**
     * 按序号升序查询某次交接的全部检查点。
     */
    public List<TransferCheckpoint> findByTransferId(long transferId) {
        return jdbc.query(
                "SELECT * FROM transfer_checkpoint WHERE transfer_id = ? ORDER BY seq",
                ROW_MAPPER, transferId);
    }

    /**
     * 按发生顺序查询证物全部历史交接的检查点（含已取消/已冻结交接的旧链）。
     */
    public List<TransferCheckpoint> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM transfer_checkpoint WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class CheckpointRowMapper implements RowMapper<TransferCheckpoint> {
        @Override
        public TransferCheckpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferCheckpoint(
                    rs.getLong("id"),
                    rs.getLong("transfer_id"),
                    rs.getString("evidence_key"),
                    rs.getInt("seq"),
                    rs.getObject("deadline_utc", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}