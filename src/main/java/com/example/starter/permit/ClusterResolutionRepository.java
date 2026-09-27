package com.example.starter.permit;

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
 * 冲突簇裁决记录持久化：permit_cluster_resolution 不可变记录的插入与查询，参数化 SQL。
 */
@Repository
public class ClusterResolutionRepository {

    private static final TypeReference<List<String>> KEY_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClusterResolutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<ClusterResolution> mapper() {
        return (rs, rowNum) -> new ClusterResolution(
                rs.getString("cluster_id"),
                rs.getString("request_id"),
                readKeyList(rs.getString("observation_keys")),
                rs.getString("chosen_observation_key"),
                rs.getString("chosen_content"),
                rs.getString("chosen_permit_id"),
                rs.getString("operator"),
                rs.getTimestamp("resolved_at_utc").toInstant());
    }

    /**
     * 插入不可变裁决记录；observationKeys 以 JSON 数组原文存储。
     */
    public void insert(ClusterResolution record) {
        jdbcTemplate.update(
                "INSERT INTO permit_cluster_resolution (cluster_id, request_id, observation_keys, "
                        + "chosen_observation_key, chosen_content, chosen_permit_id, operator, "
                        + "resolved_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.clusterId(), record.requestId(), writeKeyList(record.observationKeys()),
                record.chosenObservationKey(), record.chosenContent(), record.chosenPermitId(),
                record.operator(), Timestamp.from(record.resolvedAt()));
    }

    /**
     * 按簇标识读取裁决记录；不存在返回空。
     */
    public Optional<ClusterResolution> findByClusterId(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT cluster_id, request_id, observation_keys, chosen_observation_key, "
                                + "chosen_content, chosen_permit_id, operator, resolved_at_utc "
                                + "FROM permit_cluster_resolution WHERE cluster_id = ?",
                        mapper(), clusterId)
                .stream().findFirst();
    }

    private String writeKeyList(List<String> keys) {
        try {
            return objectMapper.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cluster keys", e);
        }
    }

    private List<String> readKeyList(String json) {
        try {
            return objectMapper.readValue(json, KEY_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cluster keys", e);
        }
    }
}
