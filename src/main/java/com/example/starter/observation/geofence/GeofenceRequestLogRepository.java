package com.example.starter.observation.geofence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 地理围栏模块幂等去重持久化：geofence_request_log 仅记录成功结果，
 * 与业务变更同事务原子提交；失败回滚不占键。所有 SQL 使用参数化查询。
 */
@Repository
public class GeofenceRequestLogRepository {

    private static final RowMapper<GeofenceRequestLogEntry> ENTRY_MAPPER = (rs, rowNum) -> new GeofenceRequestLogEntry(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("operation"),
            (Integer) rs.getObject("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public GeofenceRequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按请求标识查询去重记录；不存在时返回空。
     */
    public Optional<GeofenceRequestLogEntry> find(String requestId) {
        return jdbcTemplate.query(
                        "SELECT request_id, fingerprint, operation, response_status, response_body "
                                + "FROM geofence_request_log WHERE request_id = ?",
                        ENTRY_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 在写事务内先占位插入去重记录（响应暂为空），主键冲突时由数据库串行化并发同键请求。
     * 业务失败时随事务回滚，不占用该键。
     */
    public void insertPlaceholder(String requestId, String fingerprint, String operation) {
        jdbcTemplate.update(
                "INSERT INTO geofence_request_log (request_id, fingerprint, operation, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                requestId, fingerprint, operation);
    }

    /**
     * 业务成功后回填响应，与业务变更在同一事务内提交。
     */
    public void complete(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE geofence_request_log SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
