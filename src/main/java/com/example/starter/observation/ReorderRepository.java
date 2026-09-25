package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 不可变重排记录持久化：observation_reorder 仅在偏移重建改变观测当前胜出版本时写入，
 * 永不更新或删除。所有 SQL 使用参数化查询。
 */
@Repository
public class ReorderRepository {

    private static final String COLUMNS = "reorder_id, observation_id, device_id, effective_from_utc, "
            + "old_offset_seconds, new_offset_seconds, previous_submission_id, new_submission_id, "
            + "previous_order_key, new_order_key, previous_version, new_version, request_id";

    private static final RowMapper<ObservationReorder> REORDER_MAPPER = (rs, rowNum) -> new ObservationReorder(
            rs.getString("reorder_id"),
            rs.getString("observation_id"),
            rs.getString("device_id"),
            rs.getTimestamp("effective_from_utc").toInstant(),
            (Integer) rs.getObject("old_offset_seconds"),
            rs.getInt("new_offset_seconds"),
            rs.getString("previous_submission_id"),
            rs.getString("new_submission_id"),
            rs.getString("previous_order_key"),
            rs.getString("new_order_key"),
            rs.getInt("previous_version"),
            rs.getInt("new_version"),
            rs.getString("request_id"));

    private final JdbcTemplate jdbcTemplate;

    public ReorderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条不可变重排记录。
     */
    public void insert(ObservationReorder reorder) {
        jdbcTemplate.update(
                "INSERT INTO observation_reorder (" + COLUMNS + ", created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                reorder.reorderId(), reorder.observationId(), reorder.deviceId(),
                Timestamp.from(reorder.effectiveFromUtc()), reorder.oldOffsetSeconds(), reorder.newOffsetSeconds(),
                reorder.previousSubmissionId(), reorder.newSubmissionId(),
                reorder.previousOrderKey(), reorder.newOrderKey(),
                reorder.previousVersion(), reorder.newVersion(), reorder.requestId());
    }

    /**
     * 按观测记录查询全部重排记录，按落库时间先后排序。
     */
    public List<ObservationReorder> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_reorder WHERE observation_id = ? "
                        + "ORDER BY created_at ASC, reorder_id ASC",
                REORDER_MAPPER, observationId);
    }
}
