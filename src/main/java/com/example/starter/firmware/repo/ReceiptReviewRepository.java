package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptReview;
import com.example.starter.firmware.domain.ReviewStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Optional;

/**
 * 撤销链回执复核单数据访问。uk_review_task 保证每任务至多一条。
 */
@Repository
public class ReceiptReviewRepository {

    private static final String COLUMNS = "id, task_id, chain_id, status, reject_reason, reviewed_by,"
            + " reviewed_at_utc";

    private final JdbcTemplate jdbc;

    public ReceiptReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPending(long taskId, long chainId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO receipt_review (task_id, chain_id, status, reject_reason, reviewed_by,"
                            + " reviewed_at_utc) VALUES (?, ?, 'PENDING_REVIEW', NULL, NULL, NULL)",
                    new String[]{"id"});
            ps.setLong(1, taskId);
            ps.setLong(2, chainId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReceiptReview> findByTask(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM receipt_review WHERE task_id = ?",
                (rs, n) -> new ReceiptReview(rs.getLong("id"), rs.getLong("task_id"),
                        rs.getLong("chain_id"), ReviewStatus.valueOf(rs.getString("status")),
                        rs.getString("reject_reason"), rs.getString("reviewed_by"),
                        rs.getString("reviewed_at_utc") == null ? null
                                : Instant.parse(rs.getString("reviewed_at_utc"))),
                taskId).stream().findFirst();
    }

    public Optional<ReceiptReview> findByTaskForUpdate(long taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM receipt_review WHERE task_id = ? FOR UPDATE",
                (rs, n) -> new ReceiptReview(rs.getLong("id"), rs.getLong("task_id"),
                        rs.getLong("chain_id"), ReviewStatus.valueOf(rs.getString("status")),
                        rs.getString("reject_reason"), rs.getString("reviewed_by"),
                        rs.getString("reviewed_at_utc") == null ? null
                                : Instant.parse(rs.getString("reviewed_at_utc"))),
                taskId).stream().findFirst();
    }

    /**
     * 仅当复核单仍为 PENDING_REVIEW 时写入终态，返回影响行数。
     */
    public int decide(long taskId, ReviewStatus status, String rejectReason,
                      String reviewedBy, Instant reviewedAt) {
        return jdbc.update("UPDATE receipt_review SET status = ?, reject_reason = ?, reviewed_by = ?,"
                + " reviewed_at_utc = ? WHERE task_id = ? AND status = 'PENDING_REVIEW'",
                status.name(), rejectReason, reviewedBy, reviewedAt.toString(), taskId);
    }
}
