package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.AttributionReceipt;
import com.example.starter.exposure.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 归因回执流水数据访问。流水只追加、不修改、不删除；
 * (request_id, reservation_id) 唯一约束保证批量回执中同一预占不重复出现。
 */
@Repository
public class AttributionReceiptRepository {

    private final JdbcTemplate jdbc;

    public AttributionReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AttributionReceipt> MAPPER = (rs, rowNum) -> new AttributionReceipt(
            rs.getString("receipt_id"),
            rs.getString("request_id"),
            rs.getString("operator_id"),
            rs.getString("campaign_id"),
            rs.getString("reservation_id"),
            rs.getString("visitor_id"),
            rs.getInt("window_version"),
            rs.getInt("attribution_window_minutes"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            rs.getLong("receipt_at_utc"),
            rs.getLong("received_at_utc"),
            ReceiptResult.valueOf(rs.getString("result")),
            (Long) rs.getObject("attributed_at_utc"),
            rs.getString("late_reason"));

    private static final String COLUMNS =
            "receipt_id, request_id, operator_id, campaign_id, reservation_id, visitor_id, "
                    + "window_version, attribution_window_minutes, window_start_utc, window_end_utc, "
                    + "receipt_at_utc, received_at_utc, result, attributed_at_utc, late_reason";

    /**
     * 追加一条不可变回执流水。
     */
    public void insert(AttributionReceipt receipt) {
        jdbc.update("INSERT INTO attribution_receipt (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                receipt.receiptId(),
                receipt.requestId(),
                receipt.operatorId(),
                receipt.campaignId(),
                receipt.reservationId(),
                receipt.visitorId(),
                receipt.windowVersion(),
                receipt.attributionWindowMinutes(),
                receipt.windowStartUtc(),
                receipt.windowEndUtc(),
                receipt.receiptAtUtc(),
                receipt.receivedAtUtc(),
                receipt.result().name(),
                receipt.attributedAtUtc(),
                receipt.lateReason());
    }

    /**
     * 按预占单查询回执历史，按受理时刻、流水编号稳定升序（只读）。
     */
    public List<AttributionReceipt> findByReservationOrdered(String reservationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attribution_receipt "
                        + "WHERE reservation_id = ? "
                        + "ORDER BY received_at_utc ASC, receipt_id ASC",
                MAPPER, reservationId);
    }

    /**
     * 按公告查询回执历史（诊断用），按受理时刻、流水编号稳定升序（只读）。
     */
    public List<AttributionReceipt> findByCampaignOrdered(String campaignId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM attribution_receipt "
                        + "WHERE campaign_id = ? "
                        + "ORDER BY received_at_utc ASC, receipt_id ASC LIMIT ?",
                MAPPER, campaignId, limit);
    }
}
