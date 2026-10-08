package com.example.starter.crosssection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 断面冲突裁决记录持久化：记录成功裁决的完整上下文，不可变、永不更新。
 * 冲突字段与选择映射以 JSON 原文存储；所有 SQL 使用参数化查询。
 */
@Repository
public class CrossSectionResolutionRepository {

    private static final TypeReference<List<String>> FIELD_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> SELECTIONS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CrossSectionResolutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入不可变裁决记录；resolutionId 主键或 (observationId, requestId) 唯一键冲突时抛出重复键异常。
     */
    public void insert(CrossSectionResolutionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO cross_section_resolution (resolution_id, observation_id, request_id, "
                        + "base_version, previous_version, new_version, candidate_section_no, candidate_x_mm, "
                        + "candidate_z_mm, conflict_fields, field_selections, operator, content_changed, "
                        + "resolved_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.resolutionId(),
                record.observationId(),
                record.requestId(),
                record.baseVersion(),
                record.previousVersion(),
                record.newVersion(),
                record.candidateSectionNo(),
                record.candidateXMm(),
                record.candidateZMm(),
                writeJson(record.conflictFields()),
                record.fieldSelections(),
                record.operator(),
                record.contentChanged(),
                Timestamp.from(record.resolvedAtUtc()));
    }

    /**
     * 按全局唯一 resolutionId 查询裁决记录；不存在返回空。
     */
    public Optional<CrossSectionResolutionRecord> findByResolutionId(String resolutionId) {
        return jdbcTemplate.query(
                        "SELECT resolution_id, observation_id, request_id, base_version, previous_version, "
                                + "new_version, candidate_section_no, candidate_x_mm, candidate_z_mm, "
                                + "conflict_fields, field_selections, operator, content_changed, resolved_at_utc "
                                + "FROM cross_section_resolution WHERE resolution_id = ?",
                        RESOLUTION_MAPPER, resolutionId)
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

    private final RowMapper<CrossSectionResolutionRecord> RESOLUTION_MAPPER = (rs, rowNum) ->
            new CrossSectionResolutionRecord(
                    rs.getString("resolution_id"),
                    rs.getString("observation_id"),
                    rs.getString("request_id"),
                    rs.getInt("base_version"),
                    rs.getInt("previous_version"),
                    rs.getInt("new_version"),
                    rs.getString("candidate_section_no"),
                    rs.getInt("candidate_x_mm"),
                    rs.getInt("candidate_z_mm"),
                    readFieldList(rs.getString("conflict_fields")),
                    rs.getString("field_selections"),
                    rs.getString("operator"),
                    rs.getTimestamp("resolved_at_utc").toInstant(),
                    rs.getBoolean("content_changed"));
}
