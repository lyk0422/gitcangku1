package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 源输入收件持久化：reading_receipt 只插不改，与业务变更同事务提交。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ReadingReceiptRepository {

    private static final RowMapper<ReadingReceipt> RECEIPT_MAPPER = (rs, rowNum) -> new ReadingReceipt(
            rs.getString("request_id"),
            rs.getString("observation_id"),
            rs.getString("operation"),
            rs.getString("dimension"),
            rs.getString("original_reading"),
            rs.getString("original_unit"),
            rs.getString("normalized_reading"),
            rs.getString("operator"),
            rs.getTimestamp("received_at_utc").toInstant());

    private static final String SELECT_COLUMNS =
            "request_id, observation_id, operation, dimension, original_reading, original_unit, "
                    + "normalized_reading, operator, received_at_utc";

    private final JdbcTemplate jdbcTemplate;

    public ReadingReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在写事务内插入一条收件记录；业务失败时随事务回滚，不留收件。
     */
    public void insert(ReadingReceipt receipt) {
        jdbcTemplate.update(
                "INSERT INTO reading_receipt (request_id, observation_id, operation, dimension, "
                        + "original_reading, original_unit, normalized_reading, operator, received_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                receipt.requestId(),
                receipt.observationId(),
                receipt.operation(),
                receipt.dimension(),
                receipt.originalReading(),
                receipt.originalUnit(),
                receipt.normalizedReading(),
                receipt.operator(),
                Timestamp.from(receipt.receivedAtUtc()));
    }

    /**
     * 按观测记录查询全部收件记录，按收件先后排序。
     */
    public List<ReadingReceipt> findByObservationId(String observationId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM reading_receipt WHERE observation_id = ? "
                        + "ORDER BY created_at ASC, request_id ASC",
                RECEIPT_MAPPER, observationId);
    }
}
