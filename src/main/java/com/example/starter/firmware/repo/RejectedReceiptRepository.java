package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RejectedReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 被拒回执数据访问。隔离门禁拒绝的进行中任务回执落库，只增不改；
 * 解除隔离后历史记录仍不可改写。
 */
@Repository
public class RejectedReceiptRepository {

    private static final RowMapper<RejectedReceipt> MAPPER = (rs, rowNum) -> new RejectedReceipt(
            rs.getLong("id"), rs.getLong("task_id"), rs.getLong("release_id"), rs.getString("device_id"),
            ReceiptResult.valueOf(rs.getString("submitted_result")), rs.getString("reject_code"),
            rs.getTimestamp("created_at").toLocalDateTime().toString());

    private static final String COLUMNS = "id, task_id, release_id, device_id, submitted_result, reject_code,"
            + " created_at";

    private final JdbcTemplate jdbc;

    public RejectedReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long taskId, long releaseId, String deviceId, ReceiptResult submittedResult,
                       String rejectCode) {
        jdbc.update("INSERT INTO rejected_receipt"
                        + " (task_id, release_id, device_id, submitted_result, reject_code)"
                        + " VALUES (?, ?, ?, ?, ?)",
                taskId, releaseId, deviceId, submittedResult.name(), rejectCode);
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
