package com.example.starter.plan;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 清场计划相关表的 JDBC 访问，全部使用参数绑定。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertPlan(String name, String boundary, Instant now) {
        return insert(
                "INSERT INTO blast_plan (name, boundary, status, occupied, current_round, created_at)"
                        + " VALUES (?, ?, 'OPEN', 0, 1, ?)",
                name, boundary, Timestamp.from(now));
    }

    public void insertCheckpoint(long planId, String code, String description) {
        jdbc.update("INSERT INTO checkpoint (plan_id, code, description) VALUES (?, ?, ?)",
                planId, code, description == null ? "" : description);
    }

    /** 按主键查询计划并加行写锁（FOR UPDATE），用于串行化签发、进入与执行。 */
    public Map<String, Object> lockPlan(long planId) {
        return queryOne("SELECT * FROM blast_plan WHERE id = ? FOR UPDATE", planId);
    }

    public Map<String, Object> findPlan(long planId) {
        return queryOne("SELECT * FROM blast_plan WHERE id = ?", planId);
    }

    public List<Map<String, Object>> findCheckpoints(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM checkpoint WHERE plan_id = ? ORDER BY id", planId);
    }

    public void updatePlanState(long planId, boolean occupied, int round) {
        jdbc.update("UPDATE blast_plan SET occupied = ?, current_round = ? WHERE id = ?",
                occupied ? 1 : 0, round, planId);
    }

    public void updatePlanStatus(long planId, String status) {
        jdbc.update("UPDATE blast_plan SET status = ? WHERE id = ?", status, planId);
    }

    public long insertConfirmation(long planId, long checkpointId, int round,
            String confirmedBy, int headcount, Instant confirmedAt) {
        return insert(
                "INSERT INTO checkpoint_confirmation"
                        + " (plan_id, checkpoint_id, round_no, confirmed_by, headcount, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                planId, checkpointId, round, confirmedBy, headcount, Timestamp.from(confirmedAt));
    }

    public List<Map<String, Object>> findConfirmations(long planId) {
        return jdbc.queryForList(
                "SELECT c.*, p.code AS checkpoint_code FROM checkpoint_confirmation c"
                        + " JOIN checkpoint p ON p.id = c.checkpoint_id"
                        + " WHERE c.plan_id = ? ORDER BY c.id",
                planId);
    }

    public long insertEntry(long planId, int round, String detectedBy, Instant detectedAt) {
        return insert(
                "INSERT INTO person_entry_event (plan_id, round_no, detected_by, detected_at)"
                        + " VALUES (?, ?, ?, ?)",
                planId, round, detectedBy, Timestamp.from(detectedAt));
    }

    public List<Map<String, Object>> findEntries(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM person_entry_event WHERE plan_id = ? ORDER BY id", planId);
    }

    public long insertCommand(long planId, int round, String requestId, String paramsHash,
            String issuedBy, Instant issuedAt, Instant expiresAt) {
        return insert(
                "INSERT INTO blast_command"
                        + " (plan_id, round_no, request_id, params_hash, issued_by, issued_at,"
                        + " expires_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
                planId, round, requestId, paramsHash, issuedBy,
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    public Map<String, Object> findCommandByRequestId(long planId, String requestId) {
        return queryOne("SELECT * FROM blast_command WHERE plan_id = ? AND request_id = ?",
                planId, requestId);
    }

    public Map<String, Object> findCommand(long commandId) {
        return queryOne("SELECT * FROM blast_command WHERE id = ?", commandId);
    }

    /** 查询计划下仍处于 ACTIVE 且未过期的指令。 */
    public List<Map<String, Object>> findLiveCommands(long planId, Instant now) {
        return jdbc.queryForList(
                "SELECT * FROM blast_command WHERE plan_id = ? AND status = 'ACTIVE'"
                        + " AND expires_at > ?",
                planId, Timestamp.from(now));
    }

    public List<Map<String, Object>> findCommands(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM blast_command WHERE plan_id = ? ORDER BY id", planId);
    }

    /** 将计划下所有 ACTIVE 指令标记为 INVALIDATED，返回受影响行数。 */
    public int invalidateActiveCommands(long planId) {
        return jdbc.update(
                "UPDATE blast_command SET status = 'INVALIDATED'"
                        + " WHERE plan_id = ? AND status = 'ACTIVE'",
                planId);
    }

    public void updateCommandStatus(long commandId, String status) {
        jdbc.update("UPDATE blast_command SET status = ? WHERE id = ?", status, commandId);
    }

    public long insertReport(long planId, long commandId, String requestId, String paramsHash,
            String reportedBy, String result, Instant reportedAt) {
        return insert(
                "INSERT INTO execution_report"
                        + " (plan_id, command_id, request_id, params_hash, reported_by, result,"
                        + " reported_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                planId, commandId, requestId, paramsHash, reportedBy, result,
                Timestamp.from(reportedAt));
    }

    public Map<String, Object> findReportByRequestId(long planId, String requestId) {
        return queryOne("SELECT * FROM execution_report WHERE plan_id = ? AND request_id = ?",
                planId, requestId);
    }

    public List<Map<String, Object>> findReports(long planId) {
        return jdbc.queryForList(
                "SELECT * FROM execution_report WHERE plan_id = ? ORDER BY id", planId);
    }
}