package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 重排记录持久化：observation_reorder 记录偏移重建引起的胜出版本变化，不可变、永不更新。
 * 所有 SQL 使用参数化查询；版本顺序列表以 JSON 原文存储。
 */
@Repository
public class ReorderRepository {

    private static final TypeReference<List<Integer>> ORDER_TYPE = new TypeReference<>() {
    };

    private static final String COLUMNS =
            "reorder_id, request_id, device_id, effective_from_utc, old_offset_seconds, new_offset_seconds, "
                    + "observation_id, old_winner_version, new_winner_version, old_order, new_order";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReorderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<ReorderRecord> reorderMapper = (rs, rowNum) -> new ReorderRecord(
            rs.getString("reorder_id"),
            rs.getString("request_id"),
            rs.getString("device_id"),
            UtcJdbc.getInstant(rs, "effective_from_utc"),
            (Integer) rs.getObject("old_offset_seconds"),
            rs.getInt("new_offset_seconds"),
            rs.getString("observation_id"),
            rs.getInt("old_winner_version"),
            rs.getInt("new_winner_version"),
            readOrder(rs.getString("old_order")),
            readOrder(rs.getString("new_order")));

    /**
     * 插入一条不可变重排记录。
     */
    public void insert(ReorderRecord record) {
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO observation_reorder (" + COLUMNS + ", created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)");
            ps.setString(1, record.reorderId());
            ps.setString(2, record.requestId());
            ps.setString(3, record.deviceId());
            UtcJdbc.setInstant(ps, 4, record.effectiveFromUtc());
            if (record.oldOffsetSeconds() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, record.oldOffsetSeconds());
            }
            ps.setInt(6, record.newOffsetSeconds());
            ps.setString(7, record.observationId());
            ps.setInt(8, record.oldWinnerVersion());
            ps.setInt(9, record.newWinnerVersion());
            ps.setString(10, writeOrder(record.oldOrder()));
            ps.setString(11, writeOrder(record.newOrder()));
            return ps;
        });
    }

    /**
     * 查询全部重排记录，按落库时间与标识排序。
     */
    public List<ReorderRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_reorder ORDER BY created_at ASC, reorder_id ASC",
                reorderMapper);
    }

    /**
     * 按观测标识查询重排记录，按落库时间与标识排序。
     */
    public List<ReorderRecord> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_reorder WHERE observation_id = ? "
                        + "ORDER BY created_at ASC, reorder_id ASC",
                reorderMapper, observationId);
    }

    private String writeOrder(List<Integer> order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize version order", e);
        }
    }

    private List<Integer> readOrder(String json) {
        try {
            return objectMapper.readValue(json, ORDER_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize version order", e);
        }
    }
}
