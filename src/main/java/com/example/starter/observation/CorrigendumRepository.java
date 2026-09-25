package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 更正附页持久化：corrigendum 附页链、corrigendum_revocation 不可变撤销记录、
 * review_flag 待复审标记。所有 SQL 使用参数化查询；字段差异以 JSON 原文存储。
 */
@Repository
public class CorrigendumRepository {

    private static final TypeReference<Map<String, FieldDiff>> DIFFS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CorrigendumRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 追加一条附页；主键 (observationId, corrVersion) 冲突时抛出重复键异常。
     */
    public void insert(CorrigendumEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO corrigendum (observation_id, corr_version, base_version, diffs, reason, collector, "
                        + "corr_key, status, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                entry.observationId(),
                entry.corrVersion(),
                entry.baseVersion(),
                writeDiffs(entry.diffs()),
                entry.reason(),
                entry.collector(),
                entry.corrKey(),
                entry.status(),
                Timestamp.from(entry.createdAtUtc()));
    }

    /**
     * 查询某观测记录的全部附页，按附页版本升序（附页链）。
     */
    public List<CorrigendumEntry> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT observation_id, corr_version, base_version, diffs, reason, collector, corr_key, status, "
                        + "created_at_utc FROM corrigendum WHERE observation_id = ? ORDER BY corr_version ASC",
                corrigendumMapper(), observationId);
    }

    /**
     * 查询指定附页版本；不存在时返回空。
     */
    public Optional<CorrigendumEntry> findByVersion(String observationId, int corrVersion) {
        return jdbcTemplate.query(
                        "SELECT observation_id, corr_version, base_version, diffs, reason, collector, corr_key, status, "
                                + "created_at_utc FROM corrigendum WHERE observation_id = ? AND corr_version = ?",
                        corrigendumMapper(), observationId, corrVersion)
                .stream().findFirst();
    }

    /**
     * 查询某观测记录当前最大附页版本号；无附页时返回空。
     */
    public Optional<Integer> findMaxCorrVersion(String observationId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT MAX(corr_version) FROM corrigendum WHERE observation_id = ?",
                Integer.class, observationId);
        return Optional.ofNullable(max);
    }

    /**
     * 将指定附页标记为已撤销；不删除行，历史保留。
     */
    public void markRevoked(String observationId, int corrVersion) {
        jdbcTemplate.update(
                "UPDATE corrigendum SET status = ? WHERE observation_id = ? AND corr_version = ?",
                CorrigendumEntry.STATUS_REVOKED, observationId, corrVersion);
    }

    /**
     * 插入一条不可变撤销记录；revocationId 主键冲突时抛出重复键异常。
     */
    public void insertRevocation(RevocationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO corrigendum_revocation (revocation_id, observation_id, corr_version, "
                        + "restored_corr_version, corr_key, operator, reason, revoked_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.revocationId(),
                record.observationId(),
                record.corrVersion(),
                record.restoredCorrVersion(),
                record.corrKey(),
                record.operator(),
                record.reason(),
                Timestamp.from(record.revokedAtUtc()));
    }

    /**
     * 查询某观测记录的全部撤销记录，按撤销时刻先后排序。
     */
    public List<RevocationRecord> findRevocationsByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT revocation_id, observation_id, corr_version, restored_corr_version, corr_key, operator, "
                        + "reason, revoked_at_utc FROM corrigendum_revocation WHERE observation_id = ? "
                        + "ORDER BY revoked_at_utc ASC, revocation_id ASC",
                revocationMapper(), observationId);
    }

    /**
     * 生成一条待复审标记。
     */
    public void insertReviewFlag(String observationId, String resolutionId, int corrVersion,
                                 String event, Instant createdAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO review_flag (observation_id, resolution_id, corr_version, event, status, "
                        + "created_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                observationId, resolutionId, corrVersion, event,
                ReviewFlagRecord.STATUS_PENDING, Timestamp.from(createdAtUtc));
    }

    /**
     * 查询某观测记录的全部待复审标记，按生成先后排序。
     */
    public List<ReviewFlagRecord> findReviewFlagsByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT flag_id, observation_id, resolution_id, corr_version, event, status, created_at_utc "
                        + "FROM review_flag WHERE observation_id = ? ORDER BY flag_id ASC",
                reviewFlagMapper(), observationId);
    }

    /**
     * 判断某观测记录是否存在待复审标记。
     */
    public boolean hasPendingReviewFlag(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_flag WHERE observation_id = ? AND status = ?",
                Integer.class, observationId, ReviewFlagRecord.STATUS_PENDING);
        return count != null && count > 0;
    }

    private RowMapper<CorrigendumEntry> corrigendumMapper() {
        return (rs, rowNum) -> new CorrigendumEntry(
                rs.getString("observation_id"),
                rs.getInt("corr_version"),
                rs.getInt("base_version"),
                readDiffs(rs.getString("diffs")),
                rs.getString("reason"),
                rs.getString("collector"),
                rs.getString("corr_key"),
                rs.getString("status"),
                rs.getTimestamp("created_at_utc").toInstant());
    }

    private RowMapper<RevocationRecord> revocationMapper() {
        return (rs, rowNum) -> new RevocationRecord(
                rs.getString("revocation_id"),
                rs.getString("observation_id"),
                rs.getInt("corr_version"),
                (Integer) rs.getObject("restored_corr_version"),
                rs.getString("corr_key"),
                rs.getString("operator"),
                rs.getString("reason"),
                rs.getTimestamp("revoked_at_utc").toInstant());
    }

    private RowMapper<ReviewFlagRecord> reviewFlagMapper() {
        return (rs, rowNum) -> new ReviewFlagRecord(
                rs.getLong("flag_id"),
                rs.getString("observation_id"),
                rs.getString("resolution_id"),
                rs.getInt("corr_version"),
                rs.getString("event"),
                rs.getString("status"),
                rs.getTimestamp("created_at_utc").toInstant());
    }

    private String writeDiffs(Map<String, FieldDiff> diffs) {
        try {
            return objectMapper.writeValueAsString(diffs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize corrigendum diffs", e);
        }
    }

    private Map<String, FieldDiff> readDiffs(String json) {
        try {
            // 保持存储时的字段顺序（LinkedHashMap），保证链查询响应稳定
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, FieldDiff>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize corrigendum diffs", e);
        }
    }
}
