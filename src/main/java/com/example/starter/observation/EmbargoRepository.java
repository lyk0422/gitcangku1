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
 * 禁运设置持久化：observation_embargo 每簇至多一行，只插不改，不可变。
 * 所有 SQL 使用参数化查询；披露范围集合以 JSON 原文存储。
 */
@Repository
public class EmbargoRepository {

    private static final TypeReference<List<String>> SCOPE_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EmbargoRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入禁运设置；observation_id 主键冲突时抛出重复键异常（禁运不可变，并发/重复设置由上层判定）。
     */
    public void insert(EmbargoRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_embargo (observation_id, embargo_until_utc, scope, operator, request_id, "
                        + "set_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.observationId(),
                Timestamp.from(record.embargoUntilUtc()),
                writeJson(record.scope()),
                record.operator(),
                record.requestId(),
                Timestamp.from(record.setAtUtc()));
    }

    /**
     * 按观测记录查询禁运设置；未设置时返回空。
     */
    public Optional<EmbargoRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, embargo_until_utc, scope, operator, request_id, set_at_utc "
                                + "FROM observation_embargo WHERE observation_id = ?",
                        ROW_MAPPER, observationId)
                .stream().findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize embargo scope", e);
        }
    }

    private List<String> readScope(String json) {
        try {
            return objectMapper.readValue(json, SCOPE_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize embargo scope", e);
        }
    }

    private final RowMapper<EmbargoRecord> ROW_MAPPER = (rs, rowNum) -> new EmbargoRecord(
            rs.getString("observation_id"),
            rs.getTimestamp("embargo_until_utc").toInstant(),
            readScope(rs.getString("scope")),
            rs.getString("operator"),
            rs.getString("request_id"),
            rs.getTimestamp("set_at_utc").toInstant());
}
