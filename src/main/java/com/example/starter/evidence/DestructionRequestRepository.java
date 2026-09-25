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
 * 销毁申请表访问。申请只追加；状态迁移均为条件更新：
 * 阻断仅允许 PENDING → HOLD_BLOCKED（阻断原因与冻结快照首次写入后不可变），
 * 完成仅允许 PENDING → COMPLETED，历史不可覆盖。
 */
@Repository
public class DestructionRequestRepository {

    private static final RequestRowMapper ROW_MAPPER = new RequestRowMapper();

    private final JdbcTemplate jdbc;

    public DestructionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新销毁申请（状态 PENDING）。
     */
    public void insert(String requestKey, List<String> evidenceKeys, String requestedBy,
                       String reason, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO destruction_request
                            (request_key, evidence_keys, requested_by, reason, status,
                             block_reason, blocked_hold_keys, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, NULL)
                        """,
                requestKey, RetentionHoldRepository.join(evidenceKeys), requestedBy, reason,
                DestructionStatus.PENDING.name(), createdAt);
    }

    /**
     * 按申请业务键查询（不加锁）。
     */
    public Optional<DestructionRequest> findByKey(String requestKey) {
        List<DestructionRequest> rows = jdbc.query(
                "SELECT * FROM destruction_request WHERE request_key = ?", ROW_MAPPER, requestKey);
        return rows.stream().findFirst();
    }

    /**
     * 按申请业务键查询并锁定申请行（SELECT ... FOR UPDATE），用于完成销毁。
     */
    public Optional<DestructionRequest> findByKeyForUpdate(String requestKey) {
        List<DestructionRequest> rows = jdbc.query(
                "SELECT * FROM destruction_request WHERE request_key = ? FOR UPDATE",
                ROW_MAPPER, requestKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部待审（PENDING）申请，用于冻结生效后的阻断扫描。
     */
    public List<DestructionRequest> findPending() {
        return jdbc.query(
                "SELECT * FROM destruction_request WHERE status = ? ORDER BY id",
                ROW_MAPPER, DestructionStatus.PENDING.name());
    }

    /**
     * 查询全部申请（按发生顺序），由服务层按证物过滤，用于历史快照查询。
     */
    public List<DestructionRequest> findAll() {
        return jdbc.query("SELECT * FROM destruction_request ORDER BY id", ROW_MAPPER);
    }

    /**
     * 阻断待审申请：仅当仍为 PENDING 时写入阻断原因与冻结键快照，已阻断/已完成记录不受影响。
     *
     * @return 是否成功阻断（false 表示已被并发阻断或完成）
     */
    public boolean markBlocked(long id, String blockReason, List<String> holdKeys,
                               LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE destruction_request
                        SET status = ?, block_reason = ?, blocked_hold_keys = ?, decided_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.HOLD_BLOCKED.name(), blockReason,
                RetentionHoldRepository.join(holdKeys), decidedAt,
                id, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 完成销毁：仅当仍为 PENDING 时迁移为 COMPLETED。
     *
     * @return 是否成功完成（false 表示已被并发阻断或完成）
     */
    public boolean markCompleted(long id, LocalDateTime decidedAt) {
        int updated = jdbc.update("""
                        UPDATE destruction_request
                        SET status = ?, decided_at = ?
                        WHERE id = ? AND status = ?
                        """,
                DestructionStatus.COMPLETED.name(), decidedAt,
                id, DestructionStatus.PENDING.name());
        return updated == 1;
    }

    private static final class RequestRowMapper implements RowMapper<DestructionRequest> {
        @Override
        public DestructionRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            String blockedHoldKeys = rs.getString("blocked_hold_keys");
            return new DestructionRequest(
                    rs.getLong("id"),
                    rs.getString("request_key"),
                    RetentionHoldRepository.split(rs.getString("evidence_keys")),
                    rs.getString("requested_by"),
                    rs.getString("reason"),
                    DestructionStatus.valueOf(rs.getString("status")),
                    rs.getString("block_reason"),
                    blockedHoldKeys == null ? null : RetentionHoldRepository.split(blockedHoldKeys),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("decided_at", LocalDateTime.class));
        }
    }
}
