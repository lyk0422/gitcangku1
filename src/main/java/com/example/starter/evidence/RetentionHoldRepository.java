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
 * 保留冻结表访问。记录只追加；解除只允许一次条件更新（解除字段必须仍为 NULL）。
 * 冻结窗口为 UTC 左闭右开，是否阻断由服务层按当前时钟判定。
 */
@Repository
public class RetentionHoldRepository {

    private static final RetentionHoldRowMapper ROW_MAPPER = new RetentionHoldRowMapper();

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条冻结记录（案件级 evidenceKey 传 null）；hold_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String holdKey, HoldScope scope, String caseKey, String evidenceKey,
                       String createdBy, String reason, LocalDateTime expireAt,
                       LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (hold_key, scope, case_key, evidence_key, created_by, reason,
                             expire_at, released_by, release_reason, released_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                        """,
                holdKey, scope.name(), caseKey, evidenceKey, createdBy, reason,
                expireAt, createdAt);
    }

    /**
     * 按冻结业务键查询。
     */
    public Optional<RetentionHold> findByHoldKey(String holdKey) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_key = ?", ROW_MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 按冻结业务键查询并锁定行（解除使用，与创建/销毁串行化）。
     */
    public Optional<RetentionHold> findByHoldKeyForUpdate(String holdKey) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE hold_key = ? FOR UPDATE", ROW_MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询案件下的全部案件级冻结（按发生顺序）。
     */
    public List<RetentionHold> findCaseHolds(String caseKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE scope = ? AND case_key = ? ORDER BY id",
                ROW_MAPPER, HoldScope.CASE.name(), caseKey);
    }

    /**
     * 查询指定证物的证物级冻结（按发生顺序）。
     */
    public List<RetentionHold> findEvidenceHolds(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE scope = ? AND evidence_key = ? ORDER BY id",
                ROW_MAPPER, HoldScope.EVIDENCE.name(), evidenceKey);
    }

    /**
     * 查询某案件下当前生效窗口可能覆盖指定时刻的案件级冻结（调用前必须已锁定案件证物行）。
     * 仅按窗口与解除状态粗筛，精确左闭右开判定由服务层完成。
     */
    public List<RetentionHold> findCaseHoldsForCheck(String caseKey, LocalDateTime now) {
        return jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE scope = ? AND case_key = ?
                          AND released_at IS NULL
                          AND created_at <= ? AND expire_at > ?
                        ORDER BY id
                        """,
                ROW_MAPPER, HoldScope.CASE.name(), caseKey, now, now);
    }

    /**
     * 查询某证物当前生效窗口可能覆盖指定时刻的证物级冻结（调用前必须已锁定证物行）。
     */
    public List<RetentionHold> findEvidenceHoldsForCheck(String evidenceKey, LocalDateTime now) {
        return jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE scope = ? AND evidence_key = ?
                          AND released_at IS NULL
                          AND created_at <= ? AND expire_at > ?
                        ORDER BY id
                        """,
                ROW_MAPPER, HoldScope.EVIDENCE.name(), evidenceKey, now, now);
    }

    /**
     * 条件解除：仅当未解除时写入解除链字段，历史不可覆盖。
     *
     * @return 是否成功解除（false 表示已被并发解除）
     */
    public boolean release(long id, String releasedBy, String releaseReason, LocalDateTime releasedAt) {
        int updated = jdbc.update("""
                        UPDATE retention_hold
                        SET released_by = ?, release_reason = ?, released_at = ?
                        WHERE id = ? AND released_at IS NULL
                        """,
                releasedBy, releaseReason, releasedAt, id);
        return updated == 1;
    }

    private static final class RetentionHoldRowMapper implements RowMapper<RetentionHold> {
        @Override
        public RetentionHold mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RetentionHold(
                    rs.getLong("id"),
                    rs.getString("hold_key"),
                    HoldScope.valueOf(rs.getString("scope")),
                    rs.getString("case_key"),
                    rs.getString("evidence_key"),
                    rs.getString("created_by"),
                    rs.getString("reason"),
                    rs.getObject("expire_at", LocalDateTime.class),
                    rs.getString("released_by"),
                    rs.getString("release_reason"),
                    rs.getObject("released_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
