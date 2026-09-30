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
 * 保留冻结表访问。冻结记录只追加，解除仅做一次条件更新。
 * 销毁判定使用 FOR UPDATE 同时锁定证物级与案件级候选冻结行，
 * 与“解除”的行更新、以及持证物行锁的冻结创建按事务提交顺序串行。
 */
@Repository
public class RetentionHoldRepository {

    private static final HoldRowMapper ROW_MAPPER = new HoldRowMapper();

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加案件级冻结（scope_key=案件键，evidence_key 为 NULL）；hold_request_id 冲突抛唯一约束异常。
     */
    public void insertCaseHold(String holdRequestId, String caseKey, String createdBy,
                               LocalDateTime expiresAt, String reason, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (hold_request_id, hold_level, case_key, scope_key, evidence_key,
                             expires_at, reason, created_by, status, created_at,
                             released_by, release_reason, release_request_id, released_at)
                        VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
                        """,
                holdRequestId, HoldLevel.CASE.name(), caseKey, caseKey,
                expiresAt, reason, createdBy, HoldStatus.ACTIVE.name(), createdAt);
    }

    /**
     * 追加单件证物级冻结（scope_key=证物键，case_key 为证物所属案件）。
     */
    public void insertEvidenceHold(String holdRequestId, String evidenceKey, String caseKey,
                                   String createdBy, LocalDateTime expiresAt, String reason,
                                   LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (hold_request_id, hold_level, case_key, scope_key, evidence_key,
                             expires_at, reason, created_by, status, created_at,
                             released_by, release_reason, release_request_id, released_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
                        """,
                holdRequestId, HoldLevel.EVIDENCE.name(), caseKey, evidenceKey, evidenceKey,
                expiresAt, reason, createdBy, HoldStatus.ACTIVE.name(), createdAt);
    }

    /**
     * 按冻结申请业务键查询（不加锁）。
     */
    public Optional<RetentionHold> findByHoldRequestId(String holdRequestId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_request_id = ?", ROW_MAPPER, holdRequestId);
        return rows.stream().findFirst();
    }

    /**
     * 按冻结申请业务键查询并锁定冻结行，用于解除操作。
     */
    public Optional<RetentionHold> findByHoldRequestIdForUpdate(String holdRequestId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_request_id = ? FOR UPDATE",
                ROW_MAPPER, holdRequestId);
        return rows.stream().findFirst();
    }

    /**
     * 条件解除：仅当冻结仍为 ACTIVE 时回填解除链字段。
     *
     * @return 是否解除成功（false 表示已解除或记录不存在）
     */
    public boolean release(long id, String releasedBy, String releaseReason,
                           String releaseRequestId, LocalDateTime releasedAtUtc) {
        int updated = jdbc.update("""
                        UPDATE retention_hold
                        SET status = ?, released_by = ?, release_reason = ?,
                            release_request_id = ?, released_at = ?
                        WHERE id = ? AND status = ?
                        """,
                HoldStatus.RELEASED.name(), releasedBy, releaseReason, releaseRequestId,
                releasedAtUtc, id, HoldStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 销毁判定：锁定指定证物的证物级冻结行（含已解除/已到期行，有效性由服务层按时钟过滤）。
     * 调用前必须已锁定证物行。
     */
    public List<RetentionHold> findEvidenceHoldsForUpdate(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE evidence_key = ? ORDER BY id FOR UPDATE",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 销毁判定：锁定所属案件的案件级冻结行。调用前必须已锁定证物行。
     */
    public List<RetentionHold> findCaseHoldsForUpdate(String caseKey) {
        return jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE hold_level = ? AND case_key = ?
                        ORDER BY id FOR UPDATE
                        """,
                ROW_MAPPER, HoldLevel.CASE.name(), caseKey);
    }

    /**
     * 查询案件下全部冻结（案件级 + 各证物级），按发生顺序。
     */
    public List<RetentionHold> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    /**
     * 仅查询案件级冻结（不加锁），用于只读视图。
     */
    public List<RetentionHold> findCaseHolds(String caseKey) {
        return jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE hold_level = ? AND case_key = ?
                        ORDER BY id
                        """,
                ROW_MAPPER, HoldLevel.CASE.name(), caseKey);
    }

    /**
     * 查询单件证物的全部证物级冻结，按发生顺序。
     */
    public List<RetentionHold> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class HoldRowMapper implements RowMapper<RetentionHold> {
        @Override
        public RetentionHold mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RetentionHold(
                    rs.getLong("id"),
                    rs.getString("hold_request_id"),
                    HoldLevel.valueOf(rs.getString("hold_level")),
                    rs.getString("case_key"),
                    rs.getString("scope_key"),
                    rs.getString("evidence_key"),
                    rs.getObject("expires_at", LocalDateTime.class),
                    rs.getString("reason"),
                    rs.getString("created_by"),
                    HoldStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getString("released_by"),
                    rs.getString("release_reason"),
                    rs.getString("release_request_id"),
                    rs.getObject("released_at", LocalDateTime.class));
        }
    }
}
