package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测更正附页持久化：维护 observation_corrigendum 附页链、corrigendum_revocation 不可变撤销记录
 * 与 re_review_marker 待复审标记。所有 SQL 使用参数化查询；差异与原值以 JSON 原文存储。
 */
@Repository
public class CorrigendumRepository {

    private static final String CORRIGENDUM_COLUMNS =
            "observation_id, corr_version, corr_key, base_version, diffs, original_values, "
                    + "reason, collector, revoked, created_at_utc";

    private static final RowMapper<CorrigendumRecord> CORRIGENDUM_MAPPER = (rs, rowNum) -> new CorrigendumRecord(
            rs.getString("observation_id"),
            rs.getInt("corr_version"),
            rs.getString("corr_key"),
            rs.getInt("base_version"),
            rs.getString("diffs"),
            rs.getString("original_values"),
            rs.getString("reason"),
            rs.getString("collector"),
            rs.getBoolean("revoked"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<RevocationRecord> REVOCATION_MAPPER = (rs, rowNum) -> new RevocationRecord(
            rs.getString("observation_id"),
            rs.getInt("corr_version"),
            rs.getString("request_id"),
            rs.getString("operator"),
            rs.getTimestamp("revoked_at_utc").toInstant());

    private static final RowMapper<ReReviewMarker> MARKER_MAPPER = (rs, rowNum) -> new ReReviewMarker(
            rs.getString("resolution_id"),
            rs.getString("observation_id"),
            rs.getInt("corr_version"),
            rs.getString("status"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public CorrigendumRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 附页链 ----------

    /**
     * 追加一条附页；主键 (observation_id, corr_version) 由写事务内的行锁保证唯一。
     */
    public void insert(CorrigendumRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_corrigendum (observation_id, corr_version, corr_key, base_version, "
                        + "diffs, original_values, reason, collector, revoked, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, CURRENT_TIMESTAMP)",
                record.observationId(), record.corrVersion(), record.corrKey(), record.baseVersion(),
                record.diffs(), record.originalValues(), record.reason(), record.collector(),
                Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按观测记录与附页版本查询附页；不存在时返回空。
     */
    public Optional<CorrigendumRecord> find(String observationId, int corrVersion) {
        return jdbcTemplate.query(
                        "SELECT " + CORRIGENDUM_COLUMNS + " FROM observation_corrigendum "
                                + "WHERE observation_id = ? AND corr_version = ?",
                        CORRIGENDUM_MAPPER, observationId, corrVersion)
                .stream().findFirst();
    }

    /**
     * 查询观测记录的最新有效（未撤销）附页；无有效附页时返回空。
     */
    public Optional<CorrigendumRecord> findLatestValid(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + CORRIGENDUM_COLUMNS + " FROM observation_corrigendum "
                                + "WHERE observation_id = ? AND revoked = FALSE "
                                + "ORDER BY corr_version DESC LIMIT 1",
                        CORRIGENDUM_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询观测记录的当前最大附页版本号；无附页时返回 0。须在持有观测行锁的写事务内调用。
     */
    public int maxCorrVersion(String observationId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(corr_version), 0) FROM observation_corrigendum WHERE observation_id = ?",
                Integer.class, observationId);
        return max == null ? 0 : max;
    }

    /**
     * 按附页版本先后查询观测记录的全部附页链（含已撤销）。
     */
    public List<CorrigendumRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + CORRIGENDUM_COLUMNS + " FROM observation_corrigendum "
                        + "WHERE observation_id = ? ORDER BY corr_version ASC",
                CORRIGENDUM_MAPPER, observationId);
    }

    /**
     * 将附页标记为已撤销；仅更新 revoked 标志，其余列保持不可变。
     */
    public void markRevoked(String observationId, int corrVersion) {
        jdbcTemplate.update(
                "UPDATE observation_corrigendum SET revoked = TRUE "
                        + "WHERE observation_id = ? AND corr_version = ?",
                observationId, corrVersion);
    }

    // ---------- 不可变撤销记录 ----------

    /**
     * 写入一条不可变撤销记录；主键 (observation_id, corr_version) 保证同一附页只撤销一次。
     */
    public void insertRevocation(RevocationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO corrigendum_revocation (observation_id, corr_version, request_id, operator, "
                        + "revoked_at_utc, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(), record.corrVersion(), record.requestId(), record.operator(),
                Timestamp.from(record.revokedAtUtc()));
    }

    /**
     * 按观测记录查询全部撤销记录，按撤销时刻先后排序。
     */
    public List<RevocationRecord> findRevocations(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, corr_version, request_id, operator, revoked_at_utc "
                        + "FROM corrigendum_revocation WHERE observation_id = ? "
                        + "ORDER BY revoked_at_utc ASC, corr_version ASC",
                REVOCATION_MAPPER, observationId);
    }

    // ---------- 待复审标记 ----------

    /**
     * 生成一条待复审标记；主键 (resolution_id, observation_id, corr_version) 保证同一附页对同一解决记录只标记一次。
     */
    public void insertMarker(ReReviewMarker marker) {
        jdbcTemplate.update(
                "INSERT INTO re_review_marker (resolution_id, observation_id, corr_version, status, "
                        + "created_at_utc, created_at) VALUES (?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)",
                marker.resolutionId(), marker.observationId(), marker.corrVersion(),
                Timestamp.from(marker.createdAtUtc()));
    }

    /**
     * 按观测记录查询全部待复审标记，按生成时刻先后排序。
     */
    public List<ReReviewMarker> findMarkers(String observationId) {
        return jdbcTemplate.query(
                "SELECT resolution_id, observation_id, corr_version, status, created_at_utc "
                        + "FROM re_review_marker WHERE observation_id = ? "
                        + "ORDER BY created_at_utc ASC, resolution_id ASC",
                MARKER_MAPPER, observationId);
    }

    /**
     * 统计观测记录的待复审标记数量。
     */
    public int countMarkers(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM re_review_marker WHERE observation_id = ? AND status = 'PENDING'",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }
}
