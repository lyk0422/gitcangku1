package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 未解决冲突状态持久化：合并冲突落痕、成功写入清除、禁运设置时校验。
 * 所有 SQL 使用参数化查询；冲突字段列表以 JSON 原文存储。
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
     * 记录（或覆盖）某簇的未解决冲突状态；并发下主键冲突时退化为更新。
     */
    public void upsert(String observationId, List<String> conflictFields, int currentVersion,
                       Instant detectedAtUtc) {
        int updated = jdbcTemplate.update(
                "UPDATE observation_open_conflict SET conflict_fields = ?, current_version = ?, "
                        + "detected_at_utc = ? WHERE observation_id = ?",
                writeJson(conflictFields), currentVersion, Timestamp.from(detectedAtUtc), observationId);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO observation_open_conflict (observation_id, conflict_fields, current_version, "
                            + "detected_at_utc, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    observationId, writeJson(conflictFields), currentVersion, Timestamp.from(detectedAtUtc));
        } catch (DuplicateKeyException e) {
            // 并发冲突落痕：另一事务已插入，改为更新
            jdbcTemplate.update(
                    "UPDATE observation_open_conflict SET conflict_fields = ?, current_version = ?, "
                            + "detected_at_utc = ? WHERE observation_id = ?",
                    writeJson(conflictFields), currentVersion, Timestamp.from(detectedAtUtc), observationId);
        }
    }

    /**
     * 清除某簇的未解决冲突状态（成功的合并/解决/删除时调用，与业务变更同事务）。
     */
    public void clear(String observationId) {
        jdbcTemplate.update("DELETE FROM observation_open_conflict WHERE observation_id = ?", observationId);
    }

    /**
     * 查询某簇的未解决冲突状态；不存在时返回空。
     */
    public Optional<OpenConflictRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, conflict_fields, current_version, detected_at_utc "
                                + "FROM observation_open_conflict WHERE observation_id = ?",
                        MAPPER, observationId)
                .stream().findFirst();
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

    private final RowMapper<OpenConflictRecord> MAPPER = (rs, rowNum) -> new OpenConflictRecord(
            rs.getString("observation_id"),
            readFieldList(rs.getString("conflict_fields")),
            rs.getInt("current_version"),
            rs.getTimestamp("detected_at_utc").toInstant());
}
