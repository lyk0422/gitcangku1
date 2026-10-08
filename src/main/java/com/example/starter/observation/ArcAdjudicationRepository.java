package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 圆弧交集裁定记录持久化：arc_adjudication 记录裁定时冻结的源圆弧与全部交集分量，
 * 不可变、永不更新。所有 SQL 使用参数化查询；源列表与分量列表以 JSON 原文存储。
 */
@Repository
public class ArcAdjudicationRepository {

    private static final TypeReference<List<ArcAdjudicationRecord.ArcSource>> SOURCE_LIST_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<CircularArc>> COMPONENT_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ArcAdjudicationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条不可变裁定记录；adjudicationId 主键或 requestId 唯一键冲突时抛出重复键异常。
     */
    public void insert(ArcAdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO arc_adjudication (adjudication_id, request_id, operator, source_count, "
                        + "sources, components, created_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.adjudicationId(),
                record.requestId(),
                record.operator(),
                record.sources().size(),
                writeJson(record.sources()),
                writeJson(record.components()),
                Timestamp.from(record.createdAtUtc()));
    }

    /**
     * 按全局唯一 adjudicationId 查询裁定记录；不存在时返回空。
     */
    public Optional<ArcAdjudicationRecord> findByAdjudicationId(String adjudicationId) {
        return jdbcTemplate.query(
                        "SELECT adjudication_id, request_id, operator, sources, components, created_at_utc "
                                + "FROM arc_adjudication WHERE adjudication_id = ?",
                        RECORD_MAPPER, adjudicationId)
                .stream().findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize adjudication fields", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize adjudication fields", e);
        }
    }

    private final RowMapper<ArcAdjudicationRecord> RECORD_MAPPER = (rs, rowNum) -> new ArcAdjudicationRecord(
            rs.getString("adjudication_id"),
            rs.getString("request_id"),
            rs.getString("operator"),
            readJson(rs.getString("sources"), SOURCE_LIST_TYPE),
            readJson(rs.getString("components"), COMPONENT_LIST_TYPE),
            rs.getTimestamp("created_at_utc").toInstant());
}
