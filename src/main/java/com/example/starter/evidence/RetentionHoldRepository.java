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
 * 保留冻结表访问。冻结记录只追加，解除仅做一次条件更新（status 必须仍为 ACTIVE）。
 * request_id 与 release_request_id 各有唯一约束，承担冻结与解除各自的幂等重放。
 */
@Repository
public class RetentionHoldRepository {

    private static final HoldRowMapper ROW_MAPPER = new HoldRowMapper();

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 ACTIVE 冻结；request_id 冲突由唯一约束拒绝（并发重放由上层捕获后回放首次结果）。
     */
    public void insert(String requestId, String requestHash, HoldScope scope, String caseKey,
                       String evidenceKey, LocalDateTime expiresUtc, String reason,
                       String createdBy, LocalDateTime nowUtc, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO retention_hold
                            (request_id, request_hash, scope, case_key, evidence_key, expires_utc,
                             reason, created_by, created_utc, status, release_request_id,
                             release_request_hash, released_by, release_reason, released_utc, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?)
                        """,
                requestId, requestHash, scope.name(), caseKey, evidenceKey, expiresUtc, reason,
                createdBy, nowUtc, HoldStatus.ACTIVE.name(), createdAt);
    }

    /**
     * 按冻结创建请求键查询（不加锁），用于顺序重放校验。
     */
    public Optional<RetentionHold> findByRequestId(String requestId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按解除请求键查询已被该解除请求处理过的冻结，用于解除重放。
     */
    public Optional<RetentionHold> findByReleaseRequestId(String releaseRequestId) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE release_request_id = ?",
                ROW_MAPPER, releaseRequestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键锁定冻结行且要求仍为 ACTIVE；并发解除时第二个事务在此等待后读到空结果。
     */
    public Optional<RetentionHold> findActiveByIdForUpdate(long id) {
        List<RetentionHold> rows = jdbc.query(
                "SELECT * FROM retention_hold WHERE id = ? AND status = ? FOR UPDATE",
                ROW_MAPPER, id, HoldStatus.ACTIVE.name());
        return rows.stream().findFirst();
    }

    /**
     * 条件解除：仅当冻结仍为 ACTIVE 时写入解除链，历史不可覆盖。
     *
     * @return 是否解除成功（false 表示已被并发解除）
     */
    public boolean release(long id, String releaseRequestId, String releaseRequestHash,
                           String releasedBy, String reason, LocalDateTime nowUtc) {
        int updated = jdbc.update("""
                        UPDATE retention_hold
                        SET status = ?, release_request_id = ?, release_request_hash = ?,
                            released_by = ?, release_reason = ?, released_utc = ?
                        WHERE id = ? AND status = ?
                        """,
                HoldStatus.RELEASED.name(), releaseRequestId, releaseRequestHash,
                releasedBy, reason, nowUtc, id, HoldStatus.ACTIVE.name());
        return updated == 1;
    }

    /**
     * 查询案件下全部冻结（案件级与证物级，含已解除/已到期），按发生顺序。
     */
    public List<RetentionHold> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE case_key = ? ORDER BY id",
                ROW_MAPPER, caseKey);
    }

    /**
     * 按证物键查询其证物级冻结（含已解除/已到期），按发生顺序。
     */
    public List<RetentionHold> findEvidenceHoldsByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM retention_hold WHERE scope = ? AND evidence_key = ? ORDER BY id",
                ROW_MAPPER, HoldScope.EVIDENCE.name(), evidenceKey);
    }

    /**
     * 查询与单件证物相关的全部冻结：所属案件的案件级冻结 + 本证物的证物级冻结，按发生顺序。
     */
    public List<RetentionHold> findForEvidence(String caseKey, String evidenceKey) {
        return jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE case_key = ? AND (scope = ? OR evidence_key = ?)
                        ORDER BY id
                        """,
                ROW_MAPPER, caseKey, HoldScope.CASE.name(), evidenceKey);
    }

    /**
     * 在已锁定目标证物行的前提下，查询指定时刻仍阻断销毁的有效冻结：
     * 案件级覆盖案件下全部证物，证物级仅匹配本证物；窗口左闭右开，expires_utc 恰等于 now 不再阻断。
     */
    public Optional<RetentionHold> findBlockingHold(String caseKey, String evidenceKey,
                                                    LocalDateTime nowUtc) {
        List<RetentionHold> rows = jdbc.query("""
                        SELECT * FROM retention_hold
                        WHERE status = ?
                          AND case_key = ?
                          AND (scope = ? OR evidence_key = ?)
                          AND expires_utc > ?
                        ORDER BY id
                        """,
                ROW_MAPPER, HoldStatus.ACTIVE.name(), caseKey, HoldScope.CASE.name(),
                evidenceKey, nowUtc);
        return rows.stream().findFirst();
    }

    private static final class HoldRowMapper implements RowMapper<RetentionHold> {
        @Override
        public RetentionHold mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RetentionHold(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("request_hash"),
                    HoldScope.valueOf(rs.getString("scope")),
                    rs.getString("case_key"),
                    rs.getString("evidence_key"),
                    rs.getObject("expires_utc", LocalDateTime.class),
                    rs.getString("reason"),
                    rs.getString("created_by"),
                    rs.getObject("created_utc", LocalDateTime.class),
                    HoldStatus.valueOf(rs.getString("status")),
                    rs.getString("release_request_id"),
                    rs.getString("release_request_hash"),
                    rs.getString("released_by"),
                    rs.getString("release_reason"),
                    rs.getObject("released_utc", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
