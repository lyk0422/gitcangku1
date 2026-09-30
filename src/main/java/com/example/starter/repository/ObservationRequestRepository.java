package com.example.starter.repository;

import com.example.starter.domain.RequestStatus;
import com.example.starter.model.ObservationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 观测申请表 JDBC 仓储。所有 DATETIME 列按 UTC 解释。
 */
@Repository
public class ObservationRequestRepository {

    private static final String COLUMNS =
            "id, request_no, project_id, instrument_id, start_at, end_at, "
                    + "duration_minutes, status, requested_at, promoted_from_request_id, promoted_at";

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insert;

    public ObservationRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("observation_request")
                .usingGeneratedKeyColumns("id");
    }

    /**
     * 插入申请并回填自增主键。
     */
    public ObservationRequest insert(ObservationRequest request) {
        Map<String, Object> params = new HashMap<>();
        params.put("request_no", request.requestNo());
        params.put("project_id", request.projectId());
        params.put("instrument_id", request.instrumentId());
        params.put("start_at", toTimestamp(request.startAt()));
        params.put("end_at", toTimestamp(request.endAt()));
        params.put("duration_minutes", request.durationMinutes());
        params.put("status", request.status().name());
        params.put("requested_at", toTimestamp(request.requestedAt()));
        params.put("promoted_from_request_id", request.promotedFromRequestId());
        params.put("promoted_at", toTimestamp(request.promotedAt()));
        Number key = insert.executeAndReturnKey(params);
        return new ObservationRequest(
                key.longValue(), request.requestNo(), request.projectId(), request.instrumentId(),
                request.startAt(), request.endAt(), request.durationMinutes(),
                request.status(), request.requestedAt(),
                request.promotedFromRequestId(), request.promotedAt());
    }

    /**
     * 按申请编号查询。
     */
    public Optional<ObservationRequest> findByNo(String requestNo) {
        List<ObservationRequest> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_request WHERE request_no = ?",
                new RequestRowMapper(), requestNo);
        return list.stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<ObservationRequest> findById(Long id) {
        List<ObservationRequest> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM observation_request WHERE id = ?",
                new RequestRowMapper(), id);
        return list.stream().findFirst();
    }

    /**
     * 更新状态。
     */
    public void updateStatus(long id, String status) {
        jdbcTemplate.update(
                "UPDATE observation_request SET status = ? WHERE id = ?", status, id);
    }

    /**
     * 标记候补申请已接替空档。
     */
    public void markPromoted(long id, long promotedFromRequestId, Instant promotedAt) {
        jdbcTemplate.update("""
                        UPDATE observation_request
                        SET status = 'PROMOTED',
                            promoted_from_request_id = ?,
                            promoted_at = ?
                        WHERE id = ?""",
                promotedFromRequestId, toTimestamp(promotedAt), id);
    }

    /**
     * 某仪器的候补队列，按申请时间、申请编号升序。
     */
    public List<ObservationRequest> findPendingByInstrument(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM observation_request"
                        + " WHERE instrument_id = ? AND status = 'PENDING'"
                        + " ORDER BY requested_at ASC, request_no ASC",
                new RequestRowMapper(), instrumentId);
    }

    /**
     * 某仪器的全部申请，按开始时刻、申请编号升序。
     */
    public List<ObservationRequest> findByInstrument(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM observation_request WHERE instrument_id = ?"
                        + " ORDER BY start_at ASC, request_no ASC",
                new RequestRowMapper(), instrumentId);
    }

    /**
     * 全部申请，按仪器、开始时刻升序。
     */
    public List<ObservationRequest> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM observation_request ORDER BY instrument_id ASC, start_at ASC, request_no ASC",
                new RequestRowMapper());
    }

    /**
     * 全部发生过接替的申请，按接替时刻升序。
     */
    public List<ObservationRequest> findPromotions() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM observation_request WHERE status = 'PROMOTED'"
                        + " ORDER BY promoted_at ASC, request_no ASC",
                new RequestRowMapper());
    }

    private static Timestamp toTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant toInstant(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /**
     * 申请行映射器，DATETIME 列固定按 UTC 解释，避免依赖 JVM 默认时区。
     */
    private static final class RequestRowMapper implements RowMapper<ObservationRequest> {

        @Override
        public ObservationRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long promotedFrom = (Long) rs.getObject("promoted_from_request_id");
            return new ObservationRequest(
                    rs.getLong("id"),
                    rs.getString("request_no"),
                    rs.getString("project_id"),
                    rs.getString("instrument_id"),
                    toInstant(rs.getTimestamp("start_at")),
                    toInstant(rs.getTimestamp("end_at")),
                    rs.getInt("duration_minutes"),
                    RequestStatus.valueOf(rs.getString("status")),
                    toInstant(rs.getTimestamp("requested_at")),
                    promotedFrom,
                    toInstant(rs.getTimestamp("promoted_at")));
        }
    }
}
