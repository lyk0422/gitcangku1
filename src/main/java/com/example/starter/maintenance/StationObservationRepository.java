package com.example.starter.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 站点观测、合并快照与人工裁决持久化。所有 SQL 使用参数化查询；业务时间一律为 UTC。
 */
@Repository
public class StationObservationRepository {

    private static final RowMapper<StationObservationRecord> OBSERVATION_MAPPER =
            (rs, rowNum) -> new StationObservationRecord(
                    rs.getString("submission_id"),
                    rs.getString("cluster_id"),
                    rs.getString("station_id"),
                    rs.getTimestamp("observed_at_utc").toInstant(),
                    rs.getTimestamp("submitted_at_utc").toInstant(),
                    rs.getString("location"),
                    rs.getString("reading"),
                    rs.getString("note"),
                    rs.getString("maintenance_flag"),
                    rs.getString("window_id"),
                    (Integer) rs.getObject("window_version"),
                    rs.getString("request_id"),
                    rs.getInt("batch_seq"));

    private static final RowMapper<MergeSnapshotRecord> SNAPSHOT_MAPPER = (rs, rowNum) -> new MergeSnapshotRecord(
            rs.getString("cluster_id"),
            rs.getString("status"),
            rs.getString("winner_submission_id"),
            rs.getInt("total_count"),
            rs.getInt("affected_count"),
            rs.getInt("eligible_count"),
            rs.getString("marks_json"),
            rs.getString("request_id"),
            rs.getTimestamp("merged_at_utc").toInstant());

    private static final RowMapper<AdjudicationRecord> ADJUDICATION_MAPPER = (rs, rowNum) -> new AdjudicationRecord(
            rs.getString("adjudication_id"),
            rs.getString("cluster_id"),
            rs.getString("chosen_submission_id"),
            rs.getString("reason"),
            rs.getString("adjudicator"),
            rs.getString("request_id"),
            rs.getTimestamp("adjudicated_at_utc").toInstant());

    private static final String OBSERVATION_COLUMNS =
            "submission_id, cluster_id, station_id, observed_at_utc, submitted_at_utc, location, reading, note, "
                    + "maintenance_flag, window_id, window_version, request_id, batch_seq";

    private final JdbcTemplate jdbcTemplate;

    public StationObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条站点观测原始记录；submission_id 主键冲突时抛出重复键异常。
     */
    public void insertObservation(StationObservationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO station_observation (submission_id, cluster_id, station_id, observed_at_utc, "
                        + "submitted_at_utc, location, reading, note, maintenance_flag, window_id, window_version, "
                        + "request_id, batch_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.submissionId(), record.clusterId(), record.stationId(),
                Timestamp.from(record.observedAtUtc()), Timestamp.from(record.submittedAtUtc()),
                record.location(), record.reading(), record.note(), record.maintenanceFlag(),
                record.windowId(), record.windowVersion(), record.requestId(), record.batchSeq());
    }

    /**
     * 按提交标识查询原始观测记录。
     */
    public Optional<StationObservationRecord> findObservation(String submissionId) {
        return jdbcTemplate.query(
                        "SELECT " + OBSERVATION_COLUMNS + " FROM station_observation WHERE submission_id = ?",
                        OBSERVATION_MAPPER, submissionId)
                .stream().findFirst();
    }

    /**
     * 查询冲突簇内全部观测，按提交受理时刻、批内序号、提交标识稳定排序。
     */
    public List<StationObservationRecord> findObservationsByCluster(String clusterId) {
        return jdbcTemplate.query(
                "SELECT " + OBSERVATION_COLUMNS + " FROM station_observation WHERE cluster_id = ? "
                        + "ORDER BY submitted_at_utc ASC, batch_seq ASC, submission_id ASC",
                OBSERVATION_MAPPER, clusterId);
    }

    /**
     * 按批次请求标识查询该批全部观测，按批内序号排序（用于幂等重放时重建批量响应）。
     */
    public List<StationObservationRecord> findObservationsByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT " + OBSERVATION_COLUMNS + " FROM station_observation WHERE request_id = ? "
                        + "ORDER BY batch_seq ASC",
                OBSERVATION_MAPPER, requestId);
    }

    /**
     * 统计冲突簇内观测条数。
     */
    public int countObservationsByCluster(String clusterId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_observation WHERE cluster_id = ?", Integer.class, clusterId);
        return count == null ? 0 : count;
    }

    /**
     * 插入合并快照；cluster_id 主键冲突（重复合并）时抛出重复键异常。
     */
    public void insertSnapshot(MergeSnapshotRecord snapshot) {
        jdbcTemplate.update(
                "INSERT INTO cluster_merge_snapshot (cluster_id, status, winner_submission_id, total_count, "
                        + "affected_count, eligible_count, marks_json, request_id, merged_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.clusterId(), snapshot.status(), snapshot.winnerSubmissionId(), snapshot.totalCount(),
                snapshot.affectedCount(), snapshot.eligibleCount(), snapshot.marksJson(), snapshot.requestId(),
                Timestamp.from(snapshot.mergedAtUtc()));
    }

    /**
     * 查询冲突簇合并快照；未合并时返回空。
     */
    public Optional<MergeSnapshotRecord> findSnapshot(String clusterId) {
        return jdbcTemplate.query(
                        "SELECT cluster_id, status, winner_submission_id, total_count, affected_count, "
                                + "eligible_count, marks_json, request_id, merged_at_utc "
                                + "FROM cluster_merge_snapshot WHERE cluster_id = ?",
                        SNAPSHOT_MAPPER, clusterId)
                .stream().findFirst();
    }

    /**
     * 插入人工裁决记录；adjudication_id 主键或 (cluster_id, request_id) 唯一键冲突时抛出重复键异常。
     */
    public void insertAdjudication(AdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO cluster_adjudication (adjudication_id, cluster_id, chosen_submission_id, reason, "
                        + "adjudicator, request_id, adjudicated_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?)",
                record.adjudicationId(), record.clusterId(), record.chosenSubmissionId(), record.reason(),
                record.adjudicator(), record.requestId(), Timestamp.from(record.adjudicatedAtUtc()));
    }

    /**
     * 按裁决标识查询裁决记录。
     */
    public Optional<AdjudicationRecord> findAdjudication(String adjudicationId) {
        return jdbcTemplate.query(
                        "SELECT adjudication_id, cluster_id, chosen_submission_id, reason, adjudicator, "
                                + "request_id, adjudicated_at_utc FROM cluster_adjudication WHERE adjudication_id = ?",
                        ADJUDICATION_MAPPER, adjudicationId)
                .stream().findFirst();
    }

    /**
     * 查询冲突簇全部人工裁决记录，按裁决时刻、裁决标识稳定排序。
     */
    public List<AdjudicationRecord> findAdjudicationsByCluster(String clusterId) {
        return jdbcTemplate.query(
                "SELECT adjudication_id, cluster_id, chosen_submission_id, reason, adjudicator, request_id, "
                        + "adjudicated_at_utc FROM cluster_adjudication WHERE cluster_id = ? "
                        + "ORDER BY adjudicated_at_utc ASC, adjudication_id ASC",
                ADJUDICATION_MAPPER, clusterId);
    }
}
