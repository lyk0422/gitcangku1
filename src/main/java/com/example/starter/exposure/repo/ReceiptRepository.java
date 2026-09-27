package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Receipt;
import com.example.starter.exposure.domain.ReceiptResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 曝光确认回执数据访问。回执行不可变：只插入与查询，不提供更新。
 */
@Repository
public class ReceiptRepository {

    private final JdbcTemplate jdbc;

    public ReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Receipt> MAPPER = (rs, rowNum) -> new Receipt(
            rs.getLong("receipt_seq"),
            rs.getString("receipt_id"),
            rs.getString("reservation_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getString("operator_id"),
            rs.getLong("receipt_at_utc"),
            rs.getLong("reserved_at_utc"),
            rs.getInt("window_version"),
            rs.getInt("window_minutes"),
            ReceiptResult.valueOf(rs.getString("result")),
            rs.getString("batch_request_id"),
            rs.getLong("created_at_utc"));

    private static final String COLUMNS =
            "receipt_seq, receipt_id, reservation_id, campaign_id, visitor_id, operator_id, "
                    + "receipt_at_utc, reserved_at_utc, window_version, window_minutes, result, "
                    + "batch_request_id, created_at_utc";

    /**
     * 插入回执并返回数据库分配的提交顺序号（receipt_seq）。
     */
    public long insert(Receipt receipt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO exposure_receipt (receipt_id, reservation_id, campaign_id, visitor_id, "
                            + "operator_id, receipt_at_utc, reserved_at_utc, window_version, window_minutes, "
                            + "result, batch_request_id, created_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, receipt.receiptId());
            ps.setString(2, receipt.reservationId());
            ps.setString(3, receipt.campaignId());
            ps.setString(4, receipt.visitorId());
            ps.setString(5, receipt.operatorId());
            ps.setLong(6, receipt.receiptAtUtc());
            ps.setLong(7, receipt.reservedAtUtc());
            ps.setInt(8, receipt.windowVersion());
            ps.setInt(9, receipt.windowMinutes());
            ps.setString(10, receipt.result().name());
            ps.setString(11, receipt.batchRequestId());
            ps.setLong(12, receipt.createdAtUtc());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to obtain receipt_seq for " + receipt.receiptId());
        }
        return key.longValue();
    }

    /** 按回执编号查询（只读）。 */
    public Optional<Receipt> findByReceiptId(String receiptId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_receipt WHERE receipt_id = ?",
                        MAPPER, receiptId)
                .stream()
                .findFirst();
    }

    /** 按预占单查询回执历史，按提交顺序号升序（只读，排序稳定）。 */
    public List<Receipt> listByReservationId(String reservationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM exposure_receipt "
                        + "WHERE reservation_id = ? ORDER BY receipt_seq ASC",
                MAPPER, reservationId);
    }

    /**
     * 按公告统计各裁决结果的回执数量；可限定回执上报时刻的 UTC 区间 [fromUtc, toUtc)，
     * 边界为 null 表示不限。只读。
     */
    public Map<ReceiptResult, Long> countByResult(String campaignId, Long fromUtc, Long toUtc) {
        StringBuilder sql = new StringBuilder(
                "SELECT result, COUNT(*) AS cnt FROM exposure_receipt WHERE campaign_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(campaignId);
        if (fromUtc != null) {
            sql.append(" AND receipt_at_utc >= ?");
            args.add(fromUtc);
        }
        if (toUtc != null) {
            sql.append(" AND receipt_at_utc < ?");
            args.add(toUtc);
        }
        sql.append(" GROUP BY result");
        Map<ReceiptResult, Long> counts = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            counts.put(ReceiptResult.valueOf(rs.getString("result")), rs.getLong("cnt"));
        }, args.toArray());
        return counts;
    }
}
