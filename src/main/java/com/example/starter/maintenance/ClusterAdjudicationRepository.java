package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 人工裁决记录持久化：记录不可变，插入后不更新、不删除。
 */
@Repository
public class ClusterAdjudicationRepository {

    private static final RowMapper<ClusterAdjudication> ADJUDICATION_MAPPER = (rs, rowNum) ->
            new ClusterAdjudication(
                    rs.getString("adjudication_id"),
                    rs.getLong("snapshot_id"),
                    rs.getString("cluster_id"),
                    rs.getString("station_id"),
                    rs.getString("observation_id"),
                    rs.getBoolean("maintenance_affected"),
                    rs.getString("reason"),
                    rs.getString("adjudicator"),
                    rs.getString("request_id"),
                    rs.getTimestamp("adjudicated_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ClusterAdjudicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入不可变裁决记录。
     */
    public void insert(ClusterAdjudication adjudication) {
        jdbcTemplate.update(
                "INSERT INTO cluster_adjudication (adjudication_id, snapshot_id, cluster_id, station_id, "
                        + "observation_id, maintenance_affected, reason, adjudicator, request_id, "
                        + "adjudicated_at_utc, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                adjudication.adjudicationId(),
                adjudication.snapshotId(),
                adjudication.clusterId(),
                adjudication.stationId(),
                adjudication.observationId(),
                adjudication.maintenanceAffected(),
                adjudication.reason(),
                adjudication.adjudicator(),
                adjudication.requestId(),
                Timestamp.from(adjudication.adjudicatedAtUtc()),
                Timestamp.from(adjudication.adjudicatedAtUtc()));
    }

    /**
     * 按全局唯一裁决标识查询。
     */
    public Optional<ClusterAdjudication> findByAdjudicationId(String adjudicationId) {
        return jdbcTemplate.query(selectAll() + " WHERE adjudication_id = ?",
                ADJUDICATION_MAPPER, adjudicationId).stream().findFirst();
    }

    /**
     * 按快照查询裁决（每个快照至多一条）。
     */
    public Optional<ClusterAdjudication> findBySnapshotId(long snapshotId) {
        return jdbcTemplate.query(selectAll() + " WHERE snapshot_id = ?",
                ADJUDICATION_MAPPER, snapshotId).stream().findFirst();
    }

    /**
     * 按冲突簇查询全部裁决历史，按裁决时刻升序。
     */
    public java.util.List<ClusterAdjudication> findByClusterId(String clusterId) {
        return jdbcTemplate.query(
                selectAll() + " WHERE cluster_id = ? ORDER BY adjudicated_at_utc ASC, adjudication_id ASC",
                ADJUDICATION_MAPPER, clusterId);
    }

    private String selectAll() {
        return "SELECT adjudication_id, snapshot_id, cluster_id, station_id, observation_id, "
                + "maintenance_affected, reason, adjudicator, request_id, adjudicated_at_utc "
                + "FROM cluster_adjudication";
    }
}
