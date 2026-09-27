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
 * 批量离线合并冲突簇持久化：merge_cluster 记录批量合并重算出的字段冲突，裁决后置 RESOLVED 并永久保留。
 * 所有 SQL 使用参数化查询；冲突字段列表以 JSON 原文存储。
 */
@Repository
public class MergeClusterRepository {

    private static final TypeReference<List<String>> FIELD_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MergeClusterRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<MergeCluster> CLUSTER_MAPPER = (rs, rowNum) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at_utc");
        return new MergeCluster(
                rs.getString("cluster_id"),
                rs.getString("batch_request_id"),
                rs.getString("observation_id"),
                rs.getInt("base_version"),
                rs.getString("candidate_location"),
                rs.getString("candidate_reading"),
                rs.getString("candidate_note"),
                readFieldList(rs.getString("conflict_fields")),
                rs.getString("status"),
                rs.getString("resolution_id"),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    /**
     * 登记一条 PENDING 冲突簇；同 (batchRequestId, observationId) 重放冲突由唯一键抛出重复键异常。
     */
    public void insertPending(MergeCluster cluster) {
        jdbcTemplate.update(
                "INSERT INTO merge_cluster (cluster_id, batch_request_id, observation_id, base_version, "
                        + "candidate_location, candidate_reading, candidate_note, conflict_fields, "
                        + "status, resolution_id, created_at, resolved_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, '" + MergeCluster.PENDING
                        + "', NULL, CURRENT_TIMESTAMP, NULL)",
                cluster.clusterId(), cluster.batchRequestId(), cluster.observationId(), cluster.baseVersion(),
                cluster.candidateLocation(), cluster.candidateReading(), cluster.candidateNote(),
                writeFieldList(cluster.conflictFields()));
    }

    /**
     * 按簇标识查询。
     */
    public Optional<MergeCluster> findById(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT cluster_id, batch_request_id, observation_id, base_version, candidate_location, "
                                + "candidate_reading, candidate_note, conflict_fields, status, resolution_id, "
                                + "resolved_at_utc FROM merge_cluster WHERE cluster_id = ?",
                        CLUSTER_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 按批量请求标识查询该批登记的全部簇。
     */
    public List<MergeCluster> findByBatch(String batchRequestId) {
        return jdbcTemplate.query(
                "SELECT cluster_id, batch_request_id, observation_id, base_version, candidate_location, "
                        + "candidate_reading, candidate_note, conflict_fields, status, resolution_id, "
                        + "resolved_at_utc FROM merge_cluster WHERE batch_request_id = ? "
                        + "ORDER BY observation_id ASC",
                CLUSTER_MAPPER, batchRequestId);
    }

    /**
     * 按观测标识查询其全部冲突簇（用于重复登记预校验与裁决后核对）。
     */
    public List<MergeCluster> findByObservation(String observationId) {
        return jdbcTemplate.query(
                "SELECT cluster_id, batch_request_id, observation_id, base_version, candidate_location, "
                        + "candidate_reading, candidate_note, conflict_fields, status, resolution_id, "
                        + "resolved_at_utc FROM merge_cluster WHERE observation_id = ? "
                        + "ORDER BY created_at ASC, cluster_id ASC",
                CLUSTER_MAPPER, observationId);
    }

    /**
     * 裁决完成：PENDING 簇置 RESOLVED，写入解决记录标识与裁决时刻。
     *
     * @return 更新行数；0 表示簇不存在或已裁决
     */
    public int markResolved(String clusterId, String resolutionId, Instant resolvedAtUtc) {
        return jdbcTemplate.update(
                "UPDATE merge_cluster SET status = '" + MergeCluster.RESOLVED
                        + "', resolution_id = ?, resolved_at_utc = ? "
                        + "WHERE cluster_id = ? AND status = '" + MergeCluster.PENDING + "'",
                resolutionId, Timestamp.from(resolvedAtUtc), clusterId);
    }

    private String writeFieldList(List<String> conflictFields) {
        try {
            return objectMapper.writeValueAsString(conflictFields);
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
}
