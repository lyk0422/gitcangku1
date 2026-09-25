package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 质量标记与复核记录持久化：维护 quality_flag 标记状态与 quality_flag_review 不可变复核记录。
 * 同观测同类别唯一 PENDING 由 pending_dedup_key 唯一索引在数据库层裁决。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class QualityFlagRepository {

    private static final String FLAG_COLUMNS =
            "flag_key, observation_id, version, category, description, submitted_by, status, "
                    + "pending_dedup_key, reviewed_by, review_reason, review_version, created_at, reviewed_at";

    private static final RowMapper<QualityFlag> FLAG_MAPPER = (rs, rowNum) -> new QualityFlag(
            rs.getString("flag_key"),
            rs.getString("observation_id"),
            rs.getInt("version"),
            QualityCategory.valueOf(rs.getString("category")),
            rs.getString("description"),
            rs.getString("submitted_by"),
            FlagStatus.valueOf(rs.getString("status")),
            rs.getString("reviewed_by"),
            rs.getString("review_reason"),
            (Integer) rs.getObject("review_version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());

    private static final RowMapper<QualityFlagReview> REVIEW_MAPPER = (rs, rowNum) -> new QualityFlagReview(
            rs.getLong("id"),
            rs.getString("flag_key"),
            rs.getString("observation_id"),
            rs.getInt("flag_version"),
            rs.getInt("review_version"),
            QualityCategory.valueOf(rs.getString("category")),
            ReviewConclusion.valueOf(rs.getString("conclusion")),
            rs.getString("reason"),
            rs.getString("reviewed_by"),
            rs.getInt("confidence_after"),
            rs.getInt("confidence_delta"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public QualityFlagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入待复核标记并写入待复核去重键（observationId#category）；
     * flagKey 主键冲突或同观测同类别已有 PENDING（去重键冲突）均抛 DuplicateKeyException，由上层裁决。
     */
    public void insert(QualityFlag flag) {
        jdbcTemplate.update(
                "INSERT INTO quality_flag (flag_key, observation_id, version, category, description, "
                        + "submitted_by, status, pending_dedup_key, reviewed_by, review_reason, "
                        + "review_version, created_at, reviewed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, NULL, NULL, NULL, CURRENT_TIMESTAMP, NULL)",
                flag.flagKey(), flag.observationId(), flag.version(), flag.category().name(),
                flag.description(), flag.submittedBy(), pendingDedupKey(flag.observationId(), flag.category()));
    }

    /**
     * 按 flagKey 读取标记（不加锁），用于查询接口。
     */
    public Optional<QualityFlag> findByFlagKey(String flagKey) {
        return jdbcTemplate.query(flagSelect() + " WHERE flag_key = ?", FLAG_MAPPER, flagKey)
                .stream().findFirst();
    }

    /**
     * 按 flagKey 读取标记并加行锁，用于复核事务内串行化对同一标记的并发操作。
     */
    public Optional<QualityFlag> findByFlagKeyForUpdate(String flagKey) {
        return jdbcTemplate.query(flagSelect() + " WHERE flag_key = ? FOR UPDATE", FLAG_MAPPER, flagKey)
                .stream().findFirst();
    }

    /**
     * 复核成功：状态置为终态、清空待复核去重键（允许该类别再次提交新标记），固化复核信息。
     */
    public void completeReview(String flagKey, FlagStatus status, String reviewedBy, String reason,
                               int reviewVersion) {
        jdbcTemplate.update(
                "UPDATE quality_flag SET status = ?, pending_dedup_key = NULL, reviewed_by = ?, "
                        + "review_reason = ?, review_version = ?, reviewed_at = CURRENT_TIMESTAMP "
                        + "WHERE flag_key = ?",
                status.name(), reviewedBy, reason, reviewVersion, flagKey);
    }

    /**
     * 观测产生新版本（合并或删除墓碑）时，在同一事务内把绑定旧版本的全部待复核标记
     * 主动转为 STALE 并清空待复核去重键：它们不再可复核，也不影响新版本；
     * 同时释放同类别去重位，使新版本上可以再次提交该类别标记。
     */
    public int stalePendingFlagsForVersion(String observationId, int version) {
        return jdbcTemplate.update(
                "UPDATE quality_flag SET status = 'STALE', pending_dedup_key = NULL "
                        + "WHERE observation_id = ? AND version = ? AND status = 'PENDING'",
                observationId, version);
    }

    /**
     * 版本已变化：条件化地把仍处于 PENDING 的标记转 STALE 并清空待复核去重键。
     * 仅 PENDING 行受影响，保证并发/重入调用幂等；STALE 不是复核，不写复核信息与 reviewed_at。
     *
     * @return 实际被更新的行数（1 表示本次完成转换，0 表示标记已不在 PENDING）
     */
    public int markStaleIfPending(String flagKey) {
        return jdbcTemplate.update(
                "UPDATE quality_flag SET status = 'STALE', pending_dedup_key = NULL "
                        + "WHERE flag_key = ? AND status = 'PENDING'",
                flagKey);
    }

    /**
     * 追加不可变复核记录，固化类别与置信度变化。
     */
    public void insertReview(QualityFlagReview review) {
        jdbcTemplate.update(
                "INSERT INTO quality_flag_review (flag_key, observation_id, flag_version, review_version, "
                        + "category, conclusion, reason, reviewed_by, confidence_after, confidence_delta, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                review.flagKey(), review.observationId(), review.flagVersion(), review.reviewVersion(),
                review.category().name(), review.conclusion().name(), review.reason(), review.reviewedBy(),
                review.confidenceAfter(), review.confidenceDelta());
    }

    /**
     * 同一观测当前版本、同一类别是否存在待复核标记（预检；并发正确性由 pending_dedup_key 唯一索引兜底）。
     * 绑定旧版本的标记在版本产生时已主动转 STALE，不占用新版本的类别去重位。
     */
    public boolean existsPending(String observationId, int currentVersion, QualityCategory category) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag "
                        + "WHERE observation_id = ? AND version = ? AND category = ? AND status = 'PENDING'",
                Integer.class, observationId, currentVersion, category.name());
        return count != null && count > 0;
    }

    /**
     * 同一观测同一版本同一类别是否已存在产生实际扣减的有效 CONFIRMED 复核。
     * 同版本同类别第二个 CONFIRMED 不再扣减；新版本（version 不同）不受影响。
     */
    public boolean existsEffectiveDeduction(String observationId, int version, QualityCategory category) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review "
                        + "WHERE observation_id = ? AND flag_version = ? AND category = ? "
                        + "AND conclusion = 'CONFIRMED' AND confidence_delta < 0",
                Integer.class, observationId, version, category.name());
        return count != null && count > 0;
    }

    /**
     * 查询某观测的全部标记（含各状态），按创建顺序返回。
     */
    public List<QualityFlag> findFlagsByObservation(String observationId) {
        return jdbcTemplate.query(
                flagSelect() + " WHERE observation_id = ? ORDER BY created_at, flag_key",
                FLAG_MAPPER, observationId);
    }

    /**
     * 查询某观测当前待复核标记清单。绑定旧版本的标记在版本产生时已主动转 STALE，
     * 因此 PENDING 标记必然仍绑定当前版本。
     */
    public List<QualityFlag> findPendingByObservation(String observationId) {
        return jdbcTemplate.query(
                flagSelect() + " WHERE observation_id = ? AND status = 'PENDING' "
                        + "ORDER BY created_at, flag_key",
                FLAG_MAPPER, observationId);
    }

    /**
     * 查询某观测的全部不可变复核记录，按写入顺序返回。
     */
    public List<QualityFlagReview> findReviewsByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT id, flag_key, observation_id, flag_version, review_version, category, conclusion, "
                        + "reason, reviewed_by, confidence_after, confidence_delta, created_at "
                        + "FROM quality_flag_review WHERE observation_id = ? ORDER BY id",
                REVIEW_MAPPER, observationId);
    }

    /**
     * 待复核去重键：观测标识与类别用分隔符拼接（分隔符不出现在类别枚举中）。
     */
    public static String pendingDedupKey(String observationId, QualityCategory category) {
        return observationId + "#" + category.name();
    }

    private String flagSelect() {
        return "SELECT " + FLAG_COLUMNS + " FROM quality_flag";
    }
}
