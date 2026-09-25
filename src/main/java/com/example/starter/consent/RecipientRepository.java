package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 数据接收方持久化访问：维护接收方登记信息与整体启用/禁用状态。
 * 所有 SQL 使用参数化占位符；时间以 UTC 的 {@link Instant} 与 TIMESTAMP 列互转。
 */
@Repository
public class RecipientRepository {

    private static final RowMapper<RecipientRow> MAPPER = (rs, rowNum) -> new RecipientRow(
            rs.getString("recipient_id"),
            RecipientStatus.valueOf(rs.getString("status")),
            rs.getString("display_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("disabled_at") == null ? null : rs.getTimestamp("disabled_at").toInstant());

    private final JdbcTemplate jdbc;

    public RecipientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 接收方行。
     *
     * @param recipientId 接收方标识
     * @param status      状态：ENABLED 可用 / DISABLED 已整体禁用
     * @param displayName 展示名
     * @param createdAt   登记时间（UTC）
     * @param disabledAt  禁用时间（UTC），未禁用为 null
     */
    public record RecipientRow(String recipientId, RecipientStatus status,
                               String displayName, Instant createdAt, Instant disabledAt) {
    }

    Optional<RecipientRow> findRecipient(String recipientId) {
        List<RecipientRow> rows = jdbc.query(
                "SELECT recipient_id, status, display_name, created_at, disabled_at"
                        + " FROM recipient WHERE recipient_id = ?",
                MAPPER, recipientId);
        return rows.stream().findFirst();
    }

    /**
     * 事务内锁定接收方行（批次查询与禁用互斥，按提交顺序裁决）。
     */
    Optional<RecipientRow> findRecipientForUpdate(String recipientId) {
        List<RecipientRow> rows = jdbc.query(
                "SELECT recipient_id, status, display_name, created_at, disabled_at"
                        + " FROM recipient WHERE recipient_id = ? FOR UPDATE",
                MAPPER, recipientId);
        return rows.stream().findFirst();
    }

    void insertRecipient(String recipientId, String displayName) {
        jdbc.update(
                "INSERT INTO recipient (recipient_id, status, display_name, created_at, disabled_at)"
                        + " VALUES (?, 'ENABLED', ?, CURRENT_TIMESTAMP, NULL)",
                recipientId, displayName);
    }

    /**
     * 仅当接收方当前为 ENABLED 时禁用；返回是否实际发生状态变更。
     */
    boolean disableRecipient(String recipientId, Instant disabledAt) {
        int updated = jdbc.update(
                "UPDATE recipient SET status = 'DISABLED', disabled_at = ?"
                        + " WHERE recipient_id = ? AND status = 'ENABLED'",
                Timestamp.from(disabledAt), recipientId);
        return updated > 0;
    }
}
