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
 * 冲突簇合并记录持久化：cluster_merge 合并时插入一行；
 * PENDING_MANUAL 行在人工裁决成功后原地流转为 RESOLVED（补齐胜出/裁决字段），其余字段与历史快照均不改写。
 * 所有 SQL 使用参数化查询；簇内提交标识列表以 JSON 原文存储。
 */
@Repository
public class ClusterMergeRepository {

    private static final TypeReference<List<String>> ID_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ClusterMergeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条簇合并记录（AUTO_MERGED 或 PENDING_MANUAL）。
     */
    public void insert(ClusterMergeRecord record) {
        jdbcTemplate.update(
                "INSERT INTO cluster_merge (merge_id, observation_id, state, base_version, new_version, "
                        + "content_changed, submission_ids, cluster_size, affected_count, winning_submission_id, "
                        + "decided_maintenance_affected, decided_window_id, decided_window_version, adjudicator, "
                        + "reason, created_at_utc, decided_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.mergeId(), record.observationId(), record.state().name(),
                record.baseVersion(), record.newVersion(), record.contentChanged(),
                writeIds(record.submissionIds()), record.clusterSize(), record.affectedCount(),
                record.winningSubmissionId(), record.decidedMaintenanceAffected(),
                record.decidedWindowId(), record.decidedWindowVersion(),
                record.adjudicator(), record.reason(),
                Timestamp.from(record.createdAtUtc()),
                record.decidedAtUtc() == null ? null : Timestamp.from(record.decidedAtUtc()));
    }

    /**
     * 人工裁决成功后流转记录：PENDING_MANUAL → RESOLVED，并补齐胜出提交、版本、内容变化、
     * 固化维护字段、裁决人、理由与裁决时刻。条件更新保证并发双裁决只有一方成功。
     */
    public int applyDecision(String mergeId, String winningSubmissionId, int newVersion,
                             boolean contentChanged, boolean decidedMaintenanceAffected,
                             String decidedWindowId, Integer decidedWindowVersion,
                             String adjudicator, String reason, Instant decidedAtUtc) {
        return jdbcTemplate.update(
                "UPDATE cluster_merge SET state = 'RESOLVED', new_version = ?, winning_submission_id = ?, "
                        + "content_changed = ?, decided_maintenance_affected = ?, decided_window_id = ?, "
                        + "decided_window_version = ?, adjudicator = ?, reason = ?, decided_at_utc = ? "
                        + "WHERE merge_id = ? AND state = 'PENDING_MANUAL'",
                newVersion, winningSubmissionId, contentChanged, decidedMaintenanceAffected,
                decidedWindowId, decidedWindowVersion, adjudicator, reason,
                Timestamp.from(decidedAtUtc), mergeId);
    }

    /**
     * 按合并标识（合并请求 requestId）查询。
     */
    public Optional<ClusterMergeRecord> findByMergeId(String mergeId) {
        return jdbcTemplate.query(
                        "SELECT merge_id, observation_id, state, base_version, new_version, content_changed, "
                                + "submission_ids, cluster_size, affected_count, winning_submission_id, "
                                + "decided_maintenance_affected, decided_window_id, decided_window_version, "
                                + "adjudicator, reason, created_at_utc, decided_at_utc "
                                + "FROM cluster_merge WHERE merge_id = ?",
                        RECORD_MAPPER, mergeId)
                .stream().findFirst();
    }

    /**
     * 按合并标识查询并加行锁；人工裁决事务借此串行化并发双裁决，输家在写入任何版本前即读到 RESOLVED。
     */
    public Optional<ClusterMergeRecord> findByMergeIdForUpdate(String mergeId) {
        return jdbcTemplate.query(
                        "SELECT merge_id, observation_id, state, base_version, new_version, content_changed, "
                                + "submission_ids, cluster_size, affected_count, winning_submission_id, "
                                + "decided_maintenance_affected, decided_window_id, decided_window_version, "
                                + "adjudicator, reason, created_at_utc, decided_at_utc "
                                + "FROM cluster_merge WHERE merge_id = ? FOR UPDATE",
                        RECORD_MAPPER, mergeId)
                .stream().findFirst();
    }

    /**
     * 按观测记录查询全部簇合并记录，按创建时刻先后排序（历史/证据查询，只读）。
     */
    public List<ClusterMergeRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT merge_id, observation_id, state, base_version, new_version, content_changed, "
                        + "submission_ids, cluster_size, affected_count, winning_submission_id, "
                        + "decided_maintenance_affected, decided_window_id, decided_window_version, "
                        + "adjudicator, reason, created_at_utc, decided_at_utc "
                        + "FROM cluster_merge WHERE observation_id = ? "
                        + "ORDER BY created_at_utc ASC, merge_id ASC",
                RECORD_MAPPER, observationId);
    }

    private String writeIds(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize submission ids", e);
        }
    }

    private List<String> readIds(String json) {
        try {
            return objectMapper.readValue(json, ID_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize submission ids", e);
        }
    }

    private final RowMapper<ClusterMergeRecord> RECORD_MAPPER = (rs, rowNum) -> {
        Timestamp decided = rs.getTimestamp("decided_at_utc");
        return new ClusterMergeRecord(
                rs.getString("merge_id"),
                rs.getString("observation_id"),
                ClusterMergeState.valueOf(rs.getString("state")),
                (Integer) rs.getObject("base_version"),
                (Integer) rs.getObject("new_version"),
                rs.getBoolean("content_changed"),
                List.copyOf(readIds(rs.getString("submission_ids"))),
                rs.getInt("cluster_size"),
                rs.getInt("affected_count"),
                rs.getString("winning_submission_id"),
                (Boolean) rs.getObject("decided_maintenance_affected"),
                rs.getString("decided_window_id"),
                (Integer) rs.getObject("decided_window_version"),
                rs.getString("adjudicator"),
                rs.getString("reason"),
                rs.getTimestamp("created_at_utc").toInstant(),
                decided == null ? null : decided.toInstant());
    };
}
