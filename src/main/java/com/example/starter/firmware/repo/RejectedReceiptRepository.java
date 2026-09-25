package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RejectedReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 被拒回执记录数据访问。request_id 唯一约束保证同键重试不重复落记录，历史只增不改。
 */
@Repository
public class RejectedReceiptRepository {

    private static final RowMapper<RejectedReceipt> MAPPER = (rs, rowNum) -> new RejectedReceipt(
            rs.getLong("id"), rs.getString("request_id"), rs.getLong("task_id"),
            rs.getLong("release_id"), rs.getString("device_id"),
            ReceiptResult.valueOf(rs.getString("result")), rs.getString("reason_code"),
            rs.getString("rejected_at_utc"));

    private static final String COLUMNS = "id, request_id, task_id, release_id, device_id, result,"
            + " reason_code, rejected_at_utc";

    private final JdbcTemplate jdbc;

    public RejectedReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String requestId, long taskId, long releaseId, String deviceId,
                       ReceiptResult result, String reasonCode, String rejectedAtUtc) {
        jdbc.update("INSERT INTO rejected_receipt"
                        + " (request_id, task_id, release_id, device_id, result, reason_code, rejected_at_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, taskId, releaseId, deviceId, result.name(), reasonCode, rejectedAtUtc);
    }

    public List<RejectedReceipt> findByDevice(String deviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rejected_receipt WHERE device_id = ? ORDER BY id",
                MAPPER, deviceId);
    }

    public long countByDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rejected_receipt WHERE device_id = ?", Long.class, deviceId);
        return count == null ? 0 : count;
    }
}
