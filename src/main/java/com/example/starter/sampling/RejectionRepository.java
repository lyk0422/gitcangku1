package com.example.starter.sampling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 采样拒绝记录持久化：追加式写入，不回溯改写；新许可不影响历史拒绝记录。所有 SQL 使用参数化查询。
 */
@Repository
public class RejectionRepository {

    private static final String COLUMNS = "rejection_id, scope, request_key, device_id, grid_x, grid_y, "
            + "collected_at_utc, reason, message, rejected_at_utc";

    private static final RowMapper<SamplingRejection> REJECTION_MAPPER = (rs, rowNum) -> new SamplingRejection(
            rs.getLong("rejection_id"),
            rs.getString("scope"),
            rs.getString("request_key"),
            rs.getString("device_id"),
            (Integer) rs.getObject("grid_x"),
            (Integer) rs.getObject("grid_y"),
            rs.getTimestamp("collected_at_utc") == null ? null : rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getString("reason"),
            rs.getString("message"),
            rs.getTimestamp("rejected_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RejectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条拒绝记录。
     */
    public void insert(RejectionDraft draft, Instant rejectedAtUtc) {
        jdbcTemplate.update(
                "INSERT INTO sampling_rejection (scope, request_key, device_id, grid_x, grid_y, collected_at_utc, "
                        + "reason, message, rejected_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                draft.scope(), draft.requestKey(), draft.deviceId(), draft.gridX(), draft.gridY(),
                draft.collectedAtUtc() == null ? null : Timestamp.from(draft.collectedAtUtc()),
                draft.reason(), draft.message(), Timestamp.from(rejectedAtUtc));
    }

    /**
     * 按可选条件查询拒绝记录：设备标识与请求键均可为空（不过滤），按记录先后排序。
     */
    public List<SamplingRejection> find(String deviceId, String requestKey) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sampling_rejection WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (deviceId != null) {
            sql.append(" AND device_id = ?");
            args.add(deviceId);
        }
        if (requestKey != null) {
            sql.append(" AND request_key = ?");
            args.add(requestKey);
        }
        sql.append(" ORDER BY rejection_id ASC");
        return jdbcTemplate.query(sql.toString(), REJECTION_MAPPER, args.toArray());
    }
}
