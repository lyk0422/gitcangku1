package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ExposureReceipt;
import com.example.starter.exposure.domain.SnapshotItemStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 曝光回执数据访问。receipt_key 全局唯一，用于同键重放原决议、异参 409。
 */
@Repository
public class ReceiptRepository {

    private static final RowMapper<ExposureReceipt> MAPPER = (rs, rowNum) -> new ExposureReceipt(
            rs.getString("receipt_key"),
            rs.getString("reservation_id"),
            rs.getLong("occurred_at_utc"),
            SnapshotItemStatus.valueOf(rs.getString("decision")),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public ReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExposureReceipt receipt) {
        jdbc.update("INSERT INTO exposure_receipt "
                        + "(receipt_key, reservation_id, occurred_at_utc, decision, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                receipt.receiptKey(),
                receipt.reservationId(),
                receipt.occurredAtUtc(),
                receipt.decision().name(),
                receipt.createdAtUtc());
    }

    public Optional<ExposureReceipt> findByKey(String receiptKey) {
        List<ExposureReceipt> list = jdbc.query(
                "SELECT receipt_key, reservation_id, occurred_at_utc, decision, created_at_utc "
                        + "FROM exposure_receipt WHERE receipt_key = ?",
                MAPPER, receiptKey);
        return list.stream().findFirst();
    }
}
