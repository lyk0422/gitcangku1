package com.example.starter.observation.geofence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 观测证据持久化：geofence_observation 落库后不可变，仅 status 可经人工裁决更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceObservationRepository {

    private static final String EVIDENCE_COLUMNS =
            "observation_id, request_id, batch_seq, site_id, latitude, longitude, observed_at_utc, payload, "
                    + "match_result, status, matched_fence_id, matched_fence_version, operator, submitted_at_utc";

    private static final RowMapper<ObservationEvidenceRecord> EVIDENCE_MAPPER = (rs, rowNum) -> new ObservationEvidenceRecord(
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getInt("batch_seq"),
            rs.getString("site_id"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getTimestamp("observed_at_utc").toInstant(),
            rs.getString("payload"),
            rs.getString("match_result"),
            rs.getString("status"),
            rs.getString("matched_fence_id"),
            (Integer) rs.getObject("matched_fence_version"),
            rs.getString("operator"),
            rs.getTimestamp("submitted_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public GeofenceObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按观测标识查询证据（不加锁），用于查询接口。
     */
    public Optional<ObservationEvidenceRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + EVIDENCE_COLUMNS + " FROM geofence_observation WHERE observation_id = ?",
                        EVIDENCE_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 按观测标识查询证据并加行锁（SELECT ... FOR UPDATE），用于裁决事务内串行化。
     */
    public Optional<ObservationEvidenceRecord> findForUpdate(String observationId) {
        return jdbcTemplate.query(
                        "SELECT " + EVIDENCE_COLUMNS + " FROM geofence_observation WHERE observation_id = ? FOR UPDATE",
                        EVIDENCE_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 批量查询已存在的观测标识，用于批量提交前的冲突预检。
     */
    public List<String> findExistingIds(List<String> observationIds) {
        String placeholders = String.join(", ", observationIds.stream().map(id -> "?").toList());
        return jdbcTemplate.query(
                "SELECT observation_id FROM geofence_observation WHERE observation_id IN (" + placeholders + ") "
                        + "ORDER BY observation_id ASC",
                (rs, rowNum) -> rs.getString("observation_id"), observationIds.toArray());
    }

    /**
     * 按站点查询观测证据，可按状态过滤；按提交时刻、观测标识升序（排序稳定）。
     */
    public List<ObservationEvidenceRecord> findBySite(String siteId, String status) {
        if (status == null) {
            return jdbcTemplate.query(
                    "SELECT " + EVIDENCE_COLUMNS + " FROM geofence_observation WHERE site_id = ? "
                            + "ORDER BY submitted_at_utc ASC, observation_id ASC",
                    EVIDENCE_MAPPER, siteId);
        }
        return jdbcTemplate.query(
                "SELECT " + EVIDENCE_COLUMNS + " FROM geofence_observation WHERE site_id = ? AND status = ? "
                        + "ORDER BY submitted_at_utc ASC, observation_id ASC",
                EVIDENCE_MAPPER, siteId, status);
    }

    /**
     * 插入一条观测证据；observation_id 主键冲突时抛出重复键异常。
     */
    public void insert(ObservationEvidenceRecord record) {
        jdbcTemplate.update(
                "INSERT INTO geofence_observation (observation_id, request_id, batch_seq, site_id, latitude, longitude, "
                        + "observed_at_utc, payload, match_result, status, matched_fence_id, matched_fence_version, "
                        + "operator, submitted_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(), record.requestId(), record.batchSeq(), record.siteId(),
                record.latitude(), record.longitude(), Timestamp.from(record.observedAtUtc()), record.payload(),
                record.matchResult(), record.status(), record.matchedFenceId(), record.matchedFenceVersion(),
                record.operator(), Timestamp.from(record.submittedAtUtc()));
    }

    /**
     * 人工裁决后更新当前状态；其余字段不可变。
     */
    public void updateStatus(String observationId, String status) {
        jdbcTemplate.update(
                "UPDATE geofence_observation SET status = ? WHERE observation_id = ?",
                status, observationId);
    }
}
