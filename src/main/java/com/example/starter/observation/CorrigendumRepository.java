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
 * 更正附录持久化：observation_corrigendum 只追加、不可变，永不更新或删除。
 * 所有 SQL 使用参数化查询；字段差异列表以 JSON 数组原文存储。
 */
@Repository
public class CorrigendumRepository {

    private static final TypeReference<List<FieldChange>> CHANGES_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CorrigendumRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条不可变附录；同观测记录内附录版本号唯一，并发重复版本由数据库拒绝。
     */
    public void insert(CorrigendumRecord record) {
        jdbcTemplate.update(
                "INSERT INTO observation_corrigendum (corrigendum_id, observation_id, request_id, "
                        + "corrigendum_version, previous_version, snapshot_version, changes, operator, "
                        + "submitted_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.corrigendumId(),
                record.observationId(),
                record.requestId(),
                record.corrigendumVersion(),
                record.previousVersion(),
                record.snapshotVersion(),
                writeChanges(record.changes()),
                record.operator(),
                Timestamp.from(record.submittedAtUtc()));
    }

    /**
     * 按观测记录查询全部附录，按附录版本号先后排序。
     */
    public List<CorrigendumRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT corrigendum_id, observation_id, request_id, corrigendum_version, previous_version, "
                        + "snapshot_version, changes, operator, submitted_at_utc "
                        + "FROM observation_corrigendum WHERE observation_id = ? "
                        + "ORDER BY corrigendum_version ASC",
                CORRIGENDUM_MAPPER, observationId);
    }

    /**
     * 查询指定附录版本；不存在时返回空。
     */
    public Optional<CorrigendumRecord> findByVersion(String observationId, int corrigendumVersion) {
        return jdbcTemplate.query(
                        "SELECT corrigendum_id, observation_id, request_id, corrigendum_version, previous_version, "
                                + "snapshot_version, changes, operator, submitted_at_utc "
                                + "FROM observation_corrigendum WHERE observation_id = ? AND corrigendum_version = ?",
                        CORRIGENDUM_MAPPER, observationId, corrigendumVersion)
                .stream().findFirst();
    }

    /**
     * 读取最新一条附录；无附录时返回空。
     */
    public Optional<CorrigendumRecord> findLatest(String observationId) {
        return jdbcTemplate.query(
                        "SELECT corrigendum_id, observation_id, request_id, corrigendum_version, previous_version, "
                                + "snapshot_version, changes, operator, submitted_at_utc "
                                + "FROM observation_corrigendum WHERE observation_id = ? "
                                + "ORDER BY corrigendum_version DESC LIMIT 1",
                        CORRIGENDUM_MAPPER, observationId)
                .stream().findFirst();
    }

    private String writeChanges(List<FieldChange> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize corrigendum changes", e);
        }
    }

    private List<FieldChange> readChanges(String json) {
        try {
            return objectMapper.readValue(json, CHANGES_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize corrigendum changes", e);
        }
    }

    private final RowMapper<CorrigendumRecord> CORRIGENDUM_MAPPER = (rs, rowNum) -> new CorrigendumRecord(
            rs.getString("corrigendum_id"),
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getInt("corrigendum_version"),
            rs.getInt("previous_version"),
            rs.getInt("snapshot_version"),
            readChanges(rs.getString("changes")),
            rs.getString("operator"),
            rs.getTimestamp("submitted_at_utc").toInstant());
}
