package com.example.starter.observation.geofence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 人工裁决记录持久化：geofence_adjudication 记录成功裁决的完整上下文，不可变、永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceAdjudicationRepository {

    private static final RowMapper<AdjudicationRecord> RECORD_MAPPER = (rs, rowNum) -> new AdjudicationRecord(
            rs.getString("adjudication_id"),
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getString("decision"),
            rs.getString("reason"),
            rs.getString("operator"),
            rs.getTimestamp("adjudicated_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public GeofenceAdjudicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条不可变裁决记录；adjudication_id 主键或 observation_id 唯一键冲突时抛出重复键异常。
     */
    public void insert(AdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO geofence_adjudication (adjudication_id, observation_id, request_id, decision, reason, "
                        + "operator, adjudicated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.adjudicationId(), record.observationId(), record.requestId(), record.decision(),
                record.reason(), record.operator(), Timestamp.from(record.adjudicatedAtUtc()));
    }

    /**
     * 按全局唯一 adjudicationId 查询裁决记录；不存在时返回空。
     */
    public Optional<AdjudicationRecord> findByAdjudicationId(String adjudicationId) {
        return jdbcTemplate.query(
                        "SELECT adjudication_id, observation_id, request_id, decision, reason, operator, adjudicated_at_utc "
                                + "FROM geofence_adjudication WHERE adjudication_id = ?",
                        RECORD_MAPPER, adjudicationId)
                .stream().findFirst();
    }

    /**
     * 按观测标识查询裁决记录；不存在时返回空。
     */
    public Optional<AdjudicationRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                        "SELECT adjudication_id, observation_id, request_id, decision, reason, operator, adjudicated_at_utc "
                                + "FROM geofence_adjudication WHERE observation_id = ?",
                        RECORD_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 按观测标识查询全部裁决历史，按裁决时刻、裁决标识升序（排序稳定）。
     */
    public List<AdjudicationRecord> listByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT adjudication_id, observation_id, request_id, decision, reason, operator, adjudicated_at_utc "
                        + "FROM geofence_adjudication WHERE observation_id = ? "
                        + "ORDER BY adjudicated_at_utc ASC, adjudication_id ASC",
                RECORD_MAPPER, observationId);
    }
}
