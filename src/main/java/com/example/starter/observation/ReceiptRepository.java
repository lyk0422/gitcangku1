package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 源输入收件持久化：observation_receipt 记录每次成功收件的原始值、单位与精确规范值，
 * 与业务变更同事务原子提交；失败回滚不留收件。所有 SQL 使用参数化查询。
 */
@Repository
public class ReceiptRepository {

    private static final RowMapper<ObservationReceipt> RECEIPT_MAPPER = (rs, rowNum) -> new ObservationReceipt(
            rs.getLong("receipt_id"),
            rs.getString("observation_id"),
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("dimension"),
            rs.getString("original_value"),
            rs.getString("original_unit"),
            rs.getString("normalized_value"),
            rs.getInt("result_version"),
            rs.getTimestamp("received_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条收件记录；request_id 唯一键冲突时抛出重复键异常（正常流程下不会发生，
     * 同一 requestId 的重放直接返回已存响应，不再写收件）。
     */
    public void insert(ObservationReceipt receipt) {
        jdbcTemplate.update(
                "INSERT INTO observation_receipt (observation_id, request_id, operation, dimension, "
                        + "original_value, original_unit, normalized_value, result_version, "
                        + "received_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                receipt.observationId(),
                receipt.requestId(),
                receipt.operation(),
                receipt.dimension(),
                receipt.originalValue(),
                receipt.originalUnit(),
                receipt.normalizedValue(),
                receipt.resultVersion(),
                Timestamp.from(receipt.receivedAtUtc()));
    }

    /**
     * 按 observationId 查询全部收件记录，按收件时刻先后排序。
     */
    public List<ObservationReceipt> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT receipt_id, observation_id, request_id, operation, dimension, "
                        + "original_value, original_unit, normalized_value, result_version, received_at_utc "
                        + "FROM observation_receipt WHERE observation_id = ? "
                        + "ORDER BY received_at_utc ASC, receipt_id ASC",
                RECEIPT_MAPPER, observationId);
    }
}
