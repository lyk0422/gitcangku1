package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基准重算记录持久化：frame_recalc 固化新旧簇快照与参数版本，不可变、永不更新。
 * 所有 SQL 使用参数化查询；簇快照以 JSON 原文存储。
 */
@Repository
public class FrameRecalcRepository {

    private static final TypeReference<List<ClusterSnapshot>> SNAPSHOT_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FrameRecalcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条不可变重算记录；recalcId 主键冲突时抛出重复键异常。
     */
    public void insert(FrameRecalcRecord record) {
        jdbcTemplate.update(
                "INSERT INTO frame_recalc (recalc_id, device_id, request_id, old_frame_version, "
                        + "new_frame_version, old_clusters, new_clusters, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.recalcId(), record.deviceId(), record.requestId(),
                record.oldFrameVersion(), record.newFrameVersion(),
                writeJson(record.oldClusters()), writeJson(record.newClusters()));
    }

    /**
     * 按重算记录标识查询；不存在时返回空。
     */
    public Optional<FrameRecalcRecord> findById(String recalcId) {
        return jdbcTemplate.query(
                        "SELECT recalc_id, device_id, request_id, old_frame_version, new_frame_version, "
                                + "old_clusters, new_clusters FROM frame_recalc WHERE recalc_id = ?",
                        recalcMapper(), recalcId)
                .stream().findFirst();
    }

    /**
     * 按设备标识查询全部重算记录，按落库先后排序。
     */
    public List<FrameRecalcRecord> findByDeviceId(String deviceId) {
        return jdbcTemplate.query(
                "SELECT recalc_id, device_id, request_id, old_frame_version, new_frame_version, "
                        + "old_clusters, new_clusters FROM frame_recalc WHERE device_id = ? "
                        + "ORDER BY created_at ASC, recalc_id ASC",
                recalcMapper(), deviceId);
    }

    private RowMapper<FrameRecalcRecord> recalcMapper() {
        return (rs, rowNum) -> new FrameRecalcRecord(
                rs.getString("recalc_id"),
                rs.getString("device_id"),
                rs.getString("request_id"),
                rs.getString("old_frame_version"),
                rs.getString("new_frame_version"),
                readSnapshots(rs.getString("old_clusters")),
                readSnapshots(rs.getString("new_clusters")));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cluster snapshots", e);
        }
    }

    private List<ClusterSnapshot> readSnapshots(String json) {
        try {
            return objectMapper.readValue(json, SNAPSHOT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cluster snapshots", e);
        }
    }
}
