package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 未解决冲突诊断持久化：open_conflict 每簇至多一行，合并判冲突时登记（upsert），
 * 显式解决成功后清除。所有 SQL 使用参数化查询。
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
     * 登记或刷新未解决冲突：同一观测记录重复判冲突时更新为最新一次判定结果。
     */
    public void upsert(OpenConflictRecord record) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO open_conflict (observation_id, conflict_fields, base_version, current_version, "
                            + "request_id, detected_at_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    record.observationId(),
                    writeJson(record.conflictFields()),
                    record.baseVersion(),
                    record.currentVersion(),
                    record.requestId(),
                    Timestamp.from(record.detectedAtUtc()));
        } catch (DuplicateKeyException e) {
            jdbcTemplate.update(
                    "UPDATE open_conflict SET conflict_fields = ?, base_version = ?, current_version = ?, "
                            + "request_id = ?, detected_at_utc = ? WHERE observation_id = ?",
                    writeJson(record.conflictFields()),
                    record.baseVersion(),
                    record.currentVersion(),
                    record.requestId(),
                    Timestamp.from(record.detectedAtUtc()),
                    record.observationId());
        }
    }

    /**
     * 按观测记录查询未解决冲突；无冲突时返回空。
     */
    public Optional<OpenConflictRecord> find(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, conflict_fields, base_version, current_version, request_id, "
                                + "detected_at_utc FROM open_conflict WHERE observation_id = ?",
                        ROW_MAPPER, observationId)
                .stream().findFirst();
    }

    /**
     * 查询全部未解决冲突，按观测记录标识字典序稳定排序（诊断查询用）。
     */
    public List<OpenConflictRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT observation_id, conflict_fields, base_version, current_version, request_id, "
                        + "detected_at_utc FROM open_conflict ORDER BY observation_id ASC",
                ROW_MAPPER);
    }

    /**
     * 清除指定观测记录的未解决冲突（显式解决成功后调用）；无记录时不报错。
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

    private List<String> readFields(String json) {
        try {
            return objectMapper.readValue(json, FIELD_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize conflict fields", e);
        }
    }

    private final RowMapper<OpenConflictRecord> ROW_MAPPER = (rs, rowNum) -> new OpenConflictRecord(
            rs.getString("observation_id"),
            readFields(rs.getString("conflict_fields")),
            rs.getInt("base_version"),
            rs.getInt("current_version"),
            rs.getString("request_id"),
            rs.getTimestamp("detected_at_utc").toInstant());
}
