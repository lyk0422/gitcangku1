package com.example.starter.blackstart;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 步骤确认记录仓储。记录只增不改，(plan_id, request_id) 唯一约束保证幂等。
 */
@Repository
public class AttemptRepository {

    private static final String COLUMNS = "id, plan_id, step_no, request_id, request_hash, outcome,"
            + " voltage, frequency, power_kw, device_status, reject_reason, recorded_at";

    private static final RowMapper<StepAttempt> ROW_MAPPER = (rs, rowNum) -> new StepAttempt(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("step_no"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getString("outcome"),
            rs.getBigDecimal("voltage"),
            rs.getBigDecimal("frequency"),
            rs.getBigDecimal("power_kw"),
            rs.getString("device_status"),
            rs.getString("reject_reason"),
            rs.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbcTemplate;

    public AttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条确认记录，返回带主键的完整记录。
     */
    public StepAttempt insert(StepAttempt attempt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO step_attempt (plan_id, step_no, request_id, request_hash, outcome,"
                            + " voltage, frequency, power_kw, device_status, reject_reason, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, attempt.planId());
            ps.setInt(2, attempt.stepNo());
            ps.setString(3, attempt.requestId());
            ps.setString(4, attempt.requestHash());
            ps.setString(5, attempt.outcome());
            ps.setBigDecimal(6, attempt.voltage());
            ps.setBigDecimal(7, attempt.frequency());
            ps.setBigDecimal(8, attempt.powerKw());
            ps.setString(9, attempt.deviceStatus());
            ps.setString(10, attempt.rejectReason());
            ps.setObject(11, LocalDateTime.ofInstant(attempt.recordedAt(), ZoneOffset.UTC));
            return ps;
        }, keyHolder);
        return new StepAttempt(
                keyHolder.getKey().longValue(),
                attempt.planId(), attempt.stepNo(), attempt.requestId(), attempt.requestHash(),
                attempt.outcome(), attempt.voltage(), attempt.frequency(), attempt.powerKw(),
                attempt.deviceStatus(), attempt.rejectReason(), attempt.recordedAt());
    }

    /**
     * 按计划与请求标识查询，用于幂等重放判定。
     */
    public Optional<StepAttempt> findByPlanIdAndRequestId(long planId, String requestId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM step_attempt WHERE plan_id = ? AND request_id = ?",
                        ROW_MAPPER, planId, requestId)
                .stream().findFirst();
    }

    /**
     * 查询计划的全部确认记录，按主键升序（即提交顺序）。
     */
    public List<StepAttempt> findByPlanId(long planId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM step_attempt WHERE plan_id = ? ORDER BY id",
                ROW_MAPPER, planId);
    }

    /**
     * 统计某计划某步骤的成功记录数，用于并发断言。
     */
    public int countSuccess(long planId, int stepNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM step_attempt WHERE plan_id = ? AND step_no = ? AND outcome = 'SUCCESS'",
                Integer.class, planId, stepNo);
        return count == null ? 0 : count;
    }
}