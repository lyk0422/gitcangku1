package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 交接记录表访问。记录只追加；进行中的交接通过证物行锁保证同一证物最多一条 PENDING。
 */
@Repository
public class TransferRecordRepository {

    private static final TransferRowMapper ROW_MAPPER = new TransferRowMapper();

    private final JdbcTemplate jdbc;

    public TransferRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条待接收交接记录。
     */
    public void insert(String evidenceKey, String fromCustodian, String toCustodian,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL)
                        """,
                evidenceKey, fromCustodian, toCustodian,
                TransferStatus.PENDING.name(), fromCustodian, now);
    }

    /**
     * 查询证物当前待接收的交接（调用前必须已锁定证物行）。
     */
    public Optional<TransferRecord> findPendingByEvidenceKey(String evidenceKey) {
        List<TransferRecord> rows = jdbc.query(
                "SELECT * FROM transfer_record WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, TransferStatus.PENDING.name());
        return rows.stream().findFirst();
    }

    /**
     * 将待接收交接决定为 ACCEPTED 或 CANCELLED；仅当仍为 PENDING 时生效。
     *
     * @return 是否成功决定（false 表示已被并发事务先行决定）
     */
    public boolean decide(long id, TransferStatus decided, LocalDateTime decidedAt) {
        int updated = jdbc.update(
                "UPDATE transfer_record SET status = ?, decided_at = ? WHERE id = ? AND status = ?",
                decided.name(), decidedAt, id, TransferStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 按发生顺序查询证物全部交接记录。
     */
    public List<TransferRecord> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM transfer_record WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class TransferRowMapper implements RowMapper<TransferRecord> {
        @Override
        public TransferRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TransferRecord(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("from_custodian"),
                    rs.getString("to_custodian"),
                    TransferStatus.valueOf(rs.getString("status")),
                    rs.getString("initiated_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
