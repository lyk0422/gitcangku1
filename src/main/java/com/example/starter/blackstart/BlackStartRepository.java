package com.example.starter.blackstart;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 黑启动计划与步骤记录的 JDBC 持久化。所有时刻按 UTC 写入 TIMESTAMP 列。
 */
@Repository
public class BlackStartRepository {

    private static final RowMapper<BlackStartPlan> PLAN_MAPPER = (rs, n) -> new BlackStartPlan(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getInt("current_step"),
            toInstant(rs.getObject("created_at", LocalDateTime.class)),
            toInstant(rs.getObject("completed_at", LocalDateTime.class)),
            toInstant(rs.getObject("terminated_at", LocalDateTime.class)),
            rs.getString("termination_reason"));

    private static final RowMapper<StepAttempt> ATTEMPT_MAPPER = (rs, n) -> new StepAttempt(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("step_no"),
            rs.getString("request_id"),
            rs.getString("outcome"),
            rs.getString("device_status"),
            (Double) rs.getObject("voltage_v"),
            (Double) rs.getObject("frequency_hz"),
            (Double) rs.getObject("power_kw"),
            rs.getString("reject_reason"),
            toInstant(rs.getObject("recorded_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BlackStartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPlan(BlackStartPlan plan) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO black_start_plan (status, current_step, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, plan.status());
            ps.setInt(2, plan.currentStep());
            ps.setTimestamp(3, toTimestamp(plan.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<BlackStartPlan> findPlan(long planId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM black_start_plan WHERE id = ?", PLAN_MAPPER, planId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long insertAttempt(StepAttempt attempt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO black_start_step_attempt (plan_id, step_no, request_id, outcome,"
                            + " device_status, voltage_v, frequency_hz, power_kw, reject_reason, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, attempt.planId());
            ps.setInt(2, attempt.stepNo());
            ps.setString(3, attempt.requestId());
            ps.setString(4, attempt.outcome());
            ps.setString(5, attempt.deviceStatus());
            setDouble(ps, 6, attempt.voltageV());
            setDouble(ps, 7, attempt.frequencyHz());
            setDouble(ps, 8, attempt.powerKw());
            ps.setString(9, attempt.rejectReason());
            ps.setTimestamp(10, toTimestamp(attempt.recordedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<StepAttempt> findAttemptByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM black_start_step_attempt WHERE request_id = ?",
                    ATTEMPT_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<StepAttempt> findAttemptsByPlan(long planId) {
        return jdbc.query(
                "SELECT * FROM black_start_step_attempt WHERE plan_id = ? ORDER BY id",
                ATTEMPT_MAPPER, planId);
    }

    public void insertStepSuccess(long planId, int stepNo, long attemptId, Instant recordedAt) {
        jdbc.update(
                "INSERT INTO black_start_step_success (plan_id, step_no, attempt_id, recorded_at)"
                        + " VALUES (?, ?, ?, ?)",
                planId, stepNo, attemptId, toTimestamp(recordedAt));
    }

    public List<Long> findSucceededAttemptIds(long planId) {
        return jdbc.queryForList(
                "SELECT attempt_id FROM black_start_step_success WHERE plan_id = ?",
                Long.class, planId);
    }

    /**
     * 仅当计划仍处于执行中且停留在 expectedStep 时推进步骤；
     * 第三步成功同时把计划置为 STARTED 并写入完成时刻。返回更新行数。
     */
    public int advancePlan(long planId, int expectedStep, Instant now) {
        if (expectedStep >= BlackStartPlan.TOTAL_STEPS) {
            return jdbc.update(
                    "UPDATE black_start_plan SET status = ?, completed_at = ?"
                            + " WHERE id = ? AND status = ? AND current_step = ?",
                    BlackStartPlan.STATUS_STARTED, toTimestamp(now),
                    planId, BlackStartPlan.STATUS_IN_PROGRESS, expectedStep);
        }
        return jdbc.update(
                "UPDATE black_start_plan SET current_step = current_step + 1"
                        + " WHERE id = ? AND status = ? AND current_step = ?",
                planId, BlackStartPlan.STATUS_IN_PROGRESS, expectedStep);
    }

    /** 仅当计划仍处于执行中时置为 TERMINATED。返回更新行数。 */
    public int terminatePlan(long planId, String reason, Instant now) {
        return jdbc.update(
                "UPDATE black_start_plan SET status = ?, terminated_at = ?, termination_reason = ?"
                        + " WHERE id = ? AND status = ?",
                BlackStartPlan.STATUS_TERMINATED, toTimestamp(now), reason,
                planId, BlackStartPlan.STATUS_IN_PROGRESS);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static void setDouble(PreparedStatement ps, int index, Double value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }
}