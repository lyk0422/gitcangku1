package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 保留冻结的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 * 生效冻结判定：status = 'ACTIVE' 且 expires_at 晚于当前 UTC 时刻（由调用方传入可注入时钟的当前值）。
 */
@Repository
public class RetentionHoldRepository {

    private static final String HOLD_COLUMNS = "hold_key, subject_key, purpose, epoch, legal_reason, status,"
            + " created_by, request_id, expires_at, released_by, release_note, released_at";

    private static final RowMapper<HoldRow> MAPPER = (rs, rowNum) -> new HoldRow(
            rs.getString("hold_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("legal_reason"),
            HoldStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            rs.getString("request_id"),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getString("released_by"),
            rs.getString("release_note"),
            toInstant(rs.getTimestamp("released_at")));

    private final JdbcTemplate jdbc;

    public RetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保留冻结行。
     *
     * @param holdKey     冻结标识，全局唯一
     * @param subjectKey  主体标识
     * @param purpose     用途
     * @param epoch       被冻结的授权代次
     * @param legalReason 法定事由
     * @param status      状态：ACTIVE 生效 / RELEASED 已解除；到期由 expiresAt 派生
     * @param createdBy   创建人（保留角色操作人标识）
     * @param requestId   创建本冻结的幂等请求标识
     * @param expiresAt   UTC 到期时刻
     * @param releasedBy  解除人，未解除为 null
     * @param releaseNote 解除说明，未解除为 null
     * @param releasedAt  解除时间（UTC），未解除为 null
     */
    public record HoldRow(String holdKey, String subjectKey, Purpose purpose, int epoch,
                          String legalReason, HoldStatus status, String createdBy, String requestId,
                          Instant expiresAt, String releasedBy, String releaseNote, Instant releasedAt) {
    }

    Optional<HoldRow> findByHoldKey(String holdKey) {
        List<HoldRow> rows = jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold WHERE hold_key = ?",
                MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键锁定冻结行（SELECT ... FOR UPDATE），用于序列化解除等并发操作。
     */
    Optional<HoldRow> findByHoldKeyForUpdate(String holdKey) {
        List<HoldRow> rows = jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold WHERE hold_key = ? FOR UPDATE",
                MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定代次的全部冻结（含已解除），按创建顺序返回。
     */
    List<HoldRow> findByEpoch(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY created_at, hold_key",
                MAPPER, subjectKey, purpose.name(), epoch);
    }

    /**
     * 是否存在生效冻结：ACTIVE 且未到期。
     */
    boolean hasActiveHold(String subjectKey, Purpose purpose, int epoch, Instant now) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retention_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND status = 'ACTIVE' AND expires_at > ?",
                Long.class, subjectKey, purpose.name(), epoch, Timestamp.from(now));
        return count != null && count > 0;
    }

    /**
     * 同一 epoch 同一事由是否已存在生效冻结。
     */
    boolean hasActiveHoldWithReason(String subjectKey, Purpose purpose, int epoch,
                                    String legalReason, Instant now) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retention_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND legal_reason = ?"
                        + " AND status = 'ACTIVE' AND expires_at > ?",
                Long.class, subjectKey, purpose.name(), epoch, legalReason, Timestamp.from(now));
        return count != null && count > 0;
    }

    void insert(String holdKey, String subjectKey, Purpose purpose, int epoch, String legalReason,
                String createdBy, String requestId, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO retention_hold (hold_key, subject_key, purpose, epoch, legal_reason,"
                        + " status, created_by, request_id, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                holdKey, subjectKey, purpose.name(), epoch, legalReason,
                createdBy, requestId, Timestamp.from(expiresAt));
    }

    /**
     * 仅当冻结当前为 ACTIVE 时解除；解除记录（解除人、说明、时间）写入后不可变。
     * 返回是否实际发生状态变更。
     */
    boolean release(String holdKey, String releasedBy, String note, Instant releasedAt) {
        int updated = jdbc.update(
                "UPDATE retention_hold SET status = 'RELEASED', released_by = ?,"
                        + " release_note = ?, released_at = ?"
                        + " WHERE hold_key = ? AND status = 'ACTIVE'",
                releasedBy, note, Timestamp.from(releasedAt), holdKey);
        return updated > 0;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
