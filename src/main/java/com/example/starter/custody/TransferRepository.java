package com.example.starter.custody;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;

/**
 * 移交申请表访问:签收决定通过 {@link #lockById} 行锁保证只决定一次。
 */
@Repository
public class TransferRepository {

    private static final RowMapper<TransferRecord> MAPPER = (rs, n) -> {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new TransferRecord(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("from_holder"),
                rs.getString("to_holder"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                decided == null ? null : decided.toInstant());
    };

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long batchId, String fromHolder, String toHolder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO transfer (batch_id, from_holder, to_holder, status)
                    VALUES (?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, batchId);
            ps.setString(2, fromHolder);
            ps.setString(3, toHolder);
            ps.setString(4, TransferStatus.PENDING.name());
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("transfer insert did not return a key");
        }
        return key.longValue();
    }

    public Optional<TransferRecord> findById(long id) {
        List<TransferRecord> rows = jdbc.query(
                "SELECT * FROM transfer WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按主键加行锁读取,须在事务内调用。 */
    public Optional<TransferRecord> lockById(long id) {
        List<TransferRecord> rows = jdbc.query(
                "SELECT * FROM transfer WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 是否存在待签收或已签收的移交(存在即禁止再次移交该批次)。 */
    public boolean existsPendingOrAccepted(long batchId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM transfer
                WHERE batch_id = ? AND status IN ('PENDING', 'ACCEPTED')
                """, Integer.class, batchId);
        return count != null && count > 0;
    }

    /** 是否存在待签收移交(存在即禁止拆分该批次)。 */
    public boolean existsPending(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer WHERE batch_id = ? AND status = 'PENDING'",
                Integer.class, batchId);
        return count != null && count > 0;
    }

    public void decide(long id, TransferStatus status, String rejectReason, Instant decidedAt) {
        jdbc.update("UPDATE transfer SET status = ?, reject_reason = ?, decided_at = ? WHERE id = ?",
                status.name(), rejectReason, Timestamp.from(decidedAt), id);
    }
}