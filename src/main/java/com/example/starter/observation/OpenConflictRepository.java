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
 * 未解决冲突状态持久化：open_conflict 每簇至多一行。所有 SQL 使用参数化查询。
 */
@Repository
public class OpenConflictRepository {

    private static final TypeReference<List<String>> FIELD_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpenConflictRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录或刷新簇的未解决冲突状态（存在则覆盖为最新一次检出结果）。
     */
    public void upsert(OpenConflictRecord record) {
        int updated = jdbcTemplate.update(
                "UPDATE open_conflict SET conflict_fields = ?, base_version = ?, current_version = ?, "
                        + "request_id = ?, detected_at_utc = ? WHERE observation_id = ?",
                writeJson(record.conflictFields()), record.baseVersion(), record.currentVersion(),
                record.requestId(), Timestamp.from(record.detectedAtUtc()), record.observationId());
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO open_conflict (observation_id, conflict_fields, base_version, current_version, "
                            + "request_id, detected_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    record.observationId(), writeJson(record.conflictFields()), record.baseVersion(),
                    record.currentVersion(), record.requestId(), Timestamp.from(record.detectedAtUtc()));
        }
    }

    /**
     * 按簇查询未解决冲突状态；无未解决冲突时返回空。
     */
    public Optional<OpenConflictRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, conflict_fields, base_version, current_version, request_id, "
                                + "detected_at_utc FROM open_conflict WHERE observation_id = ?",
                        CONFLICT_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 清除簇的未解决冲突状态（合并/裁决/删除成功时同事务调用）。
     */
    public void delete(String observationId) {
        jdbcTemplate.update("DELETE FROM open_conflict WHERE observation_id = ?", observationId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize conflict fields", e);
        }
    }

    private List<String> readFieldList(String json) {
        try {
            return objectMapper.readValue(json, FIELD_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict fields", e);
        }
    }

    private final RowMapper<OpenConflictRecord> CONFLICT_MAPPER = (rs, rowNum) -> new OpenConflictRecord(
            rs.getString("observation_id"),
            readFieldList(rs.getString("conflict_fields")),
            rs.getInt("base_version"),
            rs.getInt("current_version"),
            rs.getString("request_id"),
            rs.getTimestamp("detected_at_utc").toInstant());
}