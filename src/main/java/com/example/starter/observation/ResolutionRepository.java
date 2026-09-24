package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 冲突解决记录持久化：conflict_resolution 记录成功解决的完整上下文，不可变、永不更新。
 * 所有 SQL 使用参数化查询；冲突字段列表与选择映射以 JSON 原文存储。
 */
@Repository
public class ResolutionRepository {

    private static final TypeReference<List<String>> FIELD_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<java.util.Map<String, String>> SELECTIONS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResolutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条不可变解决记录；resolutionId 主键或 (observationId, requestId) 唯一键冲突时抛出重复键异常。
     */
    public void insert(ResolutionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO conflict_resolution (resolution_id, observation_id, request_id, "
                        + "base_version, previous_version, new_version, candidate_location, candidate_reading, "
                        + "candidate_note, conflict_fields, field_selections, operator, content_changed, "
                        + "resolved_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.resolutionId(),
                record.observationId(),
                record.requestId(),
                record.baseVersion(),
                record.previousVersion(),
                record.newVersion(),
                record.candidateLocation(),
                record.candidateReading(),
                record.candidateNote(),
                writeJson(record.conflictFields()),
                record.fieldSelections(),
                record.operator(),
                record.contentChanged(),
                Timestamp.from(record.resolvedAtUtc()));
    }

    /**
     * 按全局唯一 resolutionId 查询解决记录；不存在时返回空。
     */
    public Optional<ResolutionRecord> findByResolutionId(String resolutionId) {
        return jdbcTemplate.query(
                        "SELECT resolution_id, observation_id, request_id, base_version, previous_version, "
                                + "new_version, candidate_location, candidate_reading, candidate_note, "
                                + "conflict_fields, field_selections, operator, content_changed, resolved_at_utc "
                                + "FROM conflict_resolution WHERE resolution_id = ?",
                        RESOLUTION_MAPPER, resolutionId)
                .stream().findFirst();
    }

    /**
     * 按 observationId 按解决时刻先后查询全部解决记录。
     */
    public List<ResolutionRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT resolution_id, observation_id, request_id, base_version, previous_version, "
                        + "new_version, candidate_location, candidate_reading, candidate_note, "
                        + "conflict_fields, field_selections, operator, content_changed, resolved_at_utc "
                        + "FROM conflict_resolution WHERE observation_id = ? "
                        + "ORDER BY resolved_at_utc ASC, resolution_id ASC",
                RESOLUTION_MAPPER, observationId);
    }

    /**
     * 查询某条观测记录在目标 UTC 时刻（含）之前最近一次冲突解决记录；该时刻之前无解决记录时返回空。
     * 解决记录与其产生的版本在同一写事务提交，故按解决时刻过滤与版本提交时刻的可见性一致。
     */
    public Optional<ResolutionRecord> findLatestAsOf(String observationId, Instant asOfUtc) {
        return jdbcTemplate.query(
                        "SELECT resolution_id, observation_id, request_id, base_version, previous_version, "
                                + "new_version, candidate_location, candidate_reading, candidate_note, "
                                + "conflict_fields, field_selections, operator, content_changed, resolved_at_utc "
                                + "FROM conflict_resolution "
                                + "WHERE observation_id = ? AND resolved_at_utc <= ? "
                                + "ORDER BY resolved_at_utc DESC, resolution_id DESC LIMIT 1",
                        RESOLUTION_MAPPER, observationId, Timestamp.from(asOfUtc))
                .stream().findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize resolution fields", e);
        }
    }

    private List<String> readFieldList(String json) {
        try {
            return objectMapper.readValue(json, FIELD_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict fields", e);
        }
    }

    /**
     * 反序列化存储的选择 JSON；仅用于持久化层内部校验与比较，不参与记录不可变语义。
     */
    public java.util.Map<String, String> readSelections(String json) {
        try {
            return objectMapper.readValue(json, SELECTIONS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize field selections", e);
        }
    }

    private final RowMapper<ResolutionRecord> RESOLUTION_MAPPER = (rs, rowNum) -> new ResolutionRecord(
            rs.getString("resolution_id"),
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getInt("base_version"),
            rs.getInt("previous_version"),
            rs.getInt("new_version"),
            rs.getString("candidate_location"),
            rs.getString("candidate_reading"),
            rs.getString("candidate_note"),
            readFieldList(rs.getString("conflict_fields")),
            rs.getString("field_selections"),
            rs.getString("operator"),
            rs.getTimestamp("resolved_at_utc").toInstant(),
            rs.getBoolean("content_changed"));
}
