package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 保留冻结与解除历史的持久化访问，基于 JdbcTemplate，所有 SQL 均参数化。
 */
@Repository
public class HoldRepository {

    private static final String HOLD_COLUMNS =
            "id, hold_key, subject_key, purpose, epoch, reason, created_by, expires_at,"
                    + " status, created_at, released_at";

    private static final RowMapper<HoldRow> HOLD_MAPPER = (rs, rowNum) -> new HoldRow(
            rs.getLong("id"),
            rs.getString("hold_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("reason"),
            rs.getString("created_by"),
            rs.getTimestamp("expires_at").toInstant(),
            HoldStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("released_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<ReleaseRow> RELEASE_MAPPER = (rs, rowNum) -> new ReleaseRow(
            rs.getLong("hold_id"),
            rs.getString("hold_key"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getString("released_by"),
            rs.getString("note"),
            rs.getTimestamp("released_at").toInstant());

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保留冻结行。
     *
     * @param id         自增主键
     * @param holdKey    冻结键
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      被冻结代次
     * @param reason     法定事由
     * @param createdBy  创建人（保留角色）
     * @param expiresAt  UTC 到期时刻
     * @param status     ACTIVE / RELEASED
     * @param createdAt  创建时间（UTC）
     * @param releasedAt 解除时间（UTC），未解除为 null
     */
    public record HoldRow(Long id, String holdKey, String subjectKey, Purpose purpose, int epoch,
                          String reason, String createdBy, Instant expiresAt, HoldStatus status,
                          Instant createdAt, Instant releasedAt) {

        /** 按给定时刻判定是否仍具保留效力：行未解除且尚未到期。 */
        public boolean effectiveAt(Instant now) {
            return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
        }
    }

    /**
     * 解除历史行（不可变）。
     */
    public record ReleaseRow(Long holdId, String holdKey, String subjectKey, Purpose purpose, int epoch,
                             String releasedBy, String note, Instant releasedAt) {
    }

    Optional<HoldRow> findByKey(String holdKey) {
        List<HoldRow> rows = jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold WHERE hold_key = ?",
                HOLD_MAPPER, holdKey);
        return rows.stream().findFirst();
    }

    List<HoldRow> findByEpoch(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY id",
                HOLD_MAPPER, subjectKey, purpose.name(), epoch);
    }

    /** 生效冻结：status=ACTIVE 且 expires_at 晚于给定时刻。 */
    List<HoldRow> findEffectiveByEpoch(String subjectKey, Purpose purpose, int epoch, Instant now) {
        return jdbc.query(
                "SELECT " + HOLD_COLUMNS + " FROM retention_hold"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?"
                        + " AND status = 'ACTIVE' AND expires_at > ? ORDER BY id",
                HOLD_MAPPER, subjectKey, purpose.name(), epoch, Timestamp.from(now));
    }

    void insertHold(String holdKey, String subjectKey, Purpose purpose, int epoch, String reason,
                    String createdBy, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO retention_hold"
                        + " (hold_key, subject_key, purpose, epoch, reason, created_by, expires_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                holdKey, subjectKey, purpose.name(), epoch, reason, createdBy, Timestamp.from(expiresAt));
    }

    /** 仅当冻结行为 ACTIVE 时置为 RELEASED，返回是否更新成功。 */
    boolean markReleased(long holdId, Instant releasedAt) {
        int updated = jdbc.update(
                "UPDATE retention_hold SET status = 'RELEASED', released_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(releasedAt), holdId);
        return updated > 0;
    }

    void insertRelease(long holdId, String holdKey, String subjectKey, Purpose purpose, int epoch,
                       String releasedBy, String note, Instant releasedAt) {
        jdbc.update(
                "INSERT INTO retention_hold_release"
                        + " (hold_id, hold_key, subject_key, purpose, epoch, released_by, note, released_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                holdId, holdKey, subjectKey, purpose.name(), epoch, releasedBy, note, Timestamp.from(releasedAt));
    }

    Optional<ReleaseRow> findReleaseByHoldId(long holdId) {
        List<ReleaseRow> rows = jdbc.query(
                "SELECT hold_id, hold_key, subject_key, purpose, epoch, released_by, note, released_at"
                        + " FROM retention_hold_release WHERE hold_id = ?",
                RELEASE_MAPPER, holdId);
        return rows.stream().findFirst();
    }

    List<ReleaseRow> findReleasesByEpoch(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT hold_id, hold_key, subject_key, purpose, epoch, released_by, note, released_at"
                        + " FROM retention_hold_release"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? ORDER BY released_at, id",
                RELEASE_MAPPER, subjectKey, purpose.name(), epoch);
    }
}
