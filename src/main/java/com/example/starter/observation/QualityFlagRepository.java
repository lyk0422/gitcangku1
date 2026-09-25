package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 质量标记持久化：维护 quality_flag 标记、quality_flag_review 不可变复核记录
 * 与 confidence_deduction 置信度扣减记录。所有 SQL 使用参数化查询。
 */
@Repository
public class QualityFlagRepository {

    private static final RowMapper<QualityFlag> FLAG_MAPPER = (rs, rowNum) -> new QualityFlag(
            rs.getString("observation_id"),
            rs.getString("flag_key"),
            QualityCategory.valueOf(rs.getString("category")),
            rs.getString("description"),
            rs.getString("submitted_role"),
            rs.getInt("bound_version"),
            FlagStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<QualityFlagReview> REVIEW_MAPPER = (rs, rowNum) -> new QualityFlagReview(
            rs.getString("observation_id"),
            rs.getString("flag_key"),
            rs.getInt("flagged_version"),
            rs.getInt("review_version"),
            rs.getBoolean("version_consistent"),
            ReviewConclusion.valueOf(rs.getString("conclusion")),
            rs.getString("reason"),
            rs.getString("reviewer_role"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final String FLAG_COLUMNS =
            "observation_id, flag_key, category, description, submitted_role, bound_version, status, created_at";

    private final JdbcTemplate jdbcTemplate;

    public QualityFlagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按观测与标记标识查询标记；不存在时返回空。
     */
    public Optional<QualityFlag> findFlag(String observationId, String flagKey) {
        return jdbcTemplate.query(
                        "SELECT " + FLAG_COLUMNS + " FROM quality_flag "
                                + "WHERE observation_id = ? AND flag_key = ?",
                        FLAG_MAPPER, observationId, flagKey)
                .stream().findFirst();
    }

    /**
     * 查询同一观测同一类别下的待复核标记；不存在时返回空。
     */
    public Optional<QualityFlag> findPendingByCategory(String observationId, QualityCategory category) {
        return jdbcTemplate.query(
                        "SELECT " + FLAG_COLUMNS + " FROM quality_flag "
                                + "WHERE observation_id = ? AND category = ? AND status = 'PENDING_REVIEW'",
                        FLAG_MAPPER, observationId, category.name())
                .stream().findFirst();
    }

    /**
     * 插入待复核质量标记；同一观测内 flagKey 重复时抛 DuplicateKeyException。
     */
    public void insertFlag(QualityFlag flag) {
        jdbcTemplate.update(
                "INSERT INTO quality_flag (observation_id, flag_key, category, description, submitted_role, "
                        + "bound_version, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                flag.observationId(), flag.flagKey(), flag.category().name(), flag.description(),
                flag.submittedRole(), flag.boundVersion(), flag.status().name());
    }

    /**
     * 更新标记状态（如复核生效、过期转 STALE）。
     */
    public void updateStatus(String observationId, String flagKey, FlagStatus status) {
        jdbcTemplate.update(
                "UPDATE quality_flag SET status = ? WHERE observation_id = ? AND flag_key = ?",
                status.name(), observationId, flagKey);
    }

    /**
     * 追加某观测的全部标记历史，按创建时间与标记标识升序。
     */
    public List<QualityFlag> listFlags(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + FLAG_COLUMNS + " FROM quality_flag "
                        + "WHERE observation_id = ? ORDER BY created_at, flag_key",
                FLAG_MAPPER, observationId);
    }

    /**
     * 查询某观测的待复核标记清单，按创建时间与标记标识升序。
     */
    public List<QualityFlag> listPending(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + FLAG_COLUMNS + " FROM quality_flag "
                        + "WHERE observation_id = ? AND status = 'PENDING_REVIEW' ORDER BY created_at, flag_key",
                FLAG_MAPPER, observationId);
    }

    /**
     * 写入不可变复核记录；同一标记仅允许一条，重复时抛 DuplicateKeyException。
     */
    public void insertReview(QualityFlagReview review) {
        jdbcTemplate.update(
                "INSERT INTO quality_flag_review (observation_id, flag_key, flagged_version, review_version, "
                        + "version_consistent, conclusion, reason, reviewer_role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                review.observationId(), review.flagKey(), review.flaggedVersion(), review.reviewVersion(),
                review.versionConsistent(), review.conclusion().name(), review.reason(), review.reviewerRole());
    }

    /**
     * 查询某标记的复核记录；不存在时返回空。
     */
    public Optional<QualityFlagReview> findReview(String observationId, String flagKey) {
        return jdbcTemplate.query(
                        "SELECT observation_id, flag_key, flagged_version, review_version, version_consistent, "
                                + "conclusion, reason, reviewer_role, created_at FROM quality_flag_review "
                                + "WHERE observation_id = ? AND flag_key = ?",
                        REVIEW_MAPPER, observationId, flagKey)
                .stream().findFirst();
    }

    /**
     * 判断同一观测同一类别在指定版本上是否已存在置信度扣减。
     */
    public boolean deductionExists(String observationId, QualityCategory category, int appliedVersion) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM confidence_deduction "
                        + "WHERE observation_id = ? AND category = ? AND applied_version = ?",
                Integer.class, observationId, category.name(), appliedVersion);
        return count != null && count > 0;
    }

    /**
     * 写入置信度扣减记录；同一观测同一类别同一版本最多一条，重复时抛 DuplicateKeyException。
     */
    public void insertDeduction(String observationId, QualityCategory category, int appliedVersion) {
        jdbcTemplate.update(
                "INSERT INTO confidence_deduction (observation_id, category, applied_version, created_at) "
                        + "VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                observationId, category.name(), appliedVersion);
    }
}
