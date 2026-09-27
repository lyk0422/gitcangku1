package com.example.starter.permit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 拒绝原因审计持久化：独立新事务追加，业务事务回滚不影响审计留痕。参数化 SQL。
 */
@Repository
public class RejectionLogRepository {

    private static final RowMapper<RejectionEntry> ENTRY_MAPPER = (rs, rowNum) -> new RejectionEntry(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("observation_key"),
            rs.getString("device_id"),
            rs.getBigDecimal("grid_lon"),
            rs.getBigDecimal("grid_lat"),
            rs.getTimestamp("sampled_at_utc") == null ? null : rs.getTimestamp("sampled_at_utc").toInstant(),
            rs.getString("reason_code"),
            rs.getString("reason_message"),
            rs.getTimestamp("rejected_at_utc").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public RejectionLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在独立新事务中追加拒绝记录：即使外层业务事务回滚，拒绝原因仍可查询。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(String requestId, String operation, String observationKey,
                       String deviceId, java.math.BigDecimal gridLon, java.math.BigDecimal gridLat,
                       Instant sampledAt, String reasonCode, String reasonMessage, Instant rejectedAt) {
        jdbcTemplate.update(
                "INSERT INTO permit_rejection_log (request_id, operation, observation_key, device_id, "
                        + "grid_lon, grid_lat, sampled_at_utc, reason_code, reason_message, "
                        + "rejected_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                requestId, operation, observationKey, deviceId,
                gridLon, gridLat, sampledAt == null ? null : Timestamp.from(sampledAt),
                reasonCode, reasonMessage, Timestamp.from(rejectedAt));
    }

    /**
     * 按观测业务键查询拒绝历史，按拒绝时刻倒序（最近一次在前）。
     */
    public List<RejectionEntry> findByObservationKey(String observationKey) {
        return jdbcTemplate.query(
                "SELECT id, request_id, operation, observation_key, device_id, grid_lon, grid_lat, "
                        + "sampled_at_utc, reason_code, reason_message, rejected_at_utc "
                        + "FROM permit_rejection_log WHERE observation_key = ? "
                        + "ORDER BY rejected_at_utc DESC, id DESC",
                ENTRY_MAPPER, observationKey);
    }
}
