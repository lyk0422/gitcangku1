package com.example.starter.plan;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 清场业务数据访问，基于 JdbcTemplate，全部使用参数绑定 SQL。
 */
@Repository
public class PlanRepository {

    /** 计划行。 */
    public record PlanRow(long id, String planCode, String areaBoundary, String status,
                          boolean occupied, Instant createdAt) {
    }

    /** 检查轮次行。 */
    public record RoundRow(long id, long planId, long roundNo, String status,
                           Instant openedAt, Instant closedAt) {
    }

    /** 检查点确认行。 */
    public record ConfirmationRow(long id, long roundId, String checkpointCode, String confirmer,
                                  int headcount, Instant confirmedAt) {
    }

    /** 指令行。 */
    public record CommandRow(long id, long planId, long roundId, String requestId, String payloadHash,
                             String issuedBy, Instant issuedAt, Instant expiresAt, String status,
                             Instant executedAt) {
    }

    /** 执行回报行。 */
    public record ReportRow(long id, long commandId, String requestId, String payloadHash,
                            String executor, String result, Instant reportedAt) {
    }

    /** 人员进入事件行。 */
    public record PersonEntryRow(long id, long planId, String reporter,
                                 Instant occurredAt, Instant recordedAt) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getString("plan_code"), rs.getString("area_boundary"),
            rs.getString("status"), rs.getBoolean("occupied"), toInstant(rs, "created_at"));

    private static final RowMapper<RoundRow> ROUND_MAPPER = (rs, n) -> new RoundRow(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("round_no"),
            rs.getString("status"), toInstant(rs, "opened_at"), toInstant(rs, "closed_at"));

    private static final RowMapper<ConfirmationRow> CONFIRMATION_MAPPER = (rs, n) -> new ConfirmationRow(
            rs.getLong("id"), rs.getLong("round_id"), rs.getString("checkpoint_code"),
            rs.getString("confirmer"), rs.getInt("headcount"), toInstant(rs, "confirmed_at"));

    private static final RowMapper<CommandRow> COMMAND_MAPPER = (rs, n) -> new CommandRow(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("round_id"), rs.getString("request_id"),
            rs.getString("payload_hash"), rs.getString("issued_by"), toInstant(rs, "issued_at"),
            toInstant(rs, "expires_at"), rs.getString("status"), toInstant(rs, "executed_at"));

    private static final RowMapper<ReportRow> REPORT_MAPPER = (rs, n) -> new ReportRow(
            rs.getLong("id"), rs.getLong("command_id"), rs.getString("request_id"),
            rs.getString("payload_hash"), rs.getString("executor"), rs.getString("result"),
            toInstant(rs, "reported_at"));

    private static final RowMapper<PersonEntryRow> PERSON_ENTRY_MAPPER = (rs, n) -> new PersonEntryRow(
            rs.getLong("id"), rs.getLong("plan_id"), rs.getString("reporter"),
            toInstant(rs, "occurred_at"), toInstant(rs, "recorded_at"));

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<PlanRow> findPlanById(long planId) {
        return first(jdbc.query("SELECT * FROM blast_plan WHERE id = ?", PLAN_MAPPER, planId));
    }

    public Optional<PlanRow> findPlanByCode(String planCode) {
        return first(jdbc.query("SELECT * FROM blast_plan WHERE plan_code = ?", PLAN_MAPPER, planCode));
    }

    public Optional<PlanRow> lockPlanById(long planId) {
        return first(jdbc.query("SELECT * FROM blast_plan WHERE id = ? FOR UPDATE", PLAN_MAPPER, planId));
    }

    public long insertPlan(String planCode, String areaBoundary, Instant createdAt) {
        return insert("INSERT INTO blast_plan (plan_code, area_boundary, status, occupied, created_at)"
                        + " VALUES (?, ?, 'ACTIVE', FALSE, ?)",
                planCode, areaBoundary, Timestamp.from(createdAt));
    }

    public void updatePlanStatus(long planId, String status) {
        jdbc.update("UPDATE blast_plan SET status = ? WHERE id = ?", status, planId);
    }

    public void updatePlanOccupied(long planId, boolean occupied) {
        jdbc.update("UPDATE blast_plan SET occupied = ? WHERE id = ?", occupied, planId);
    }

    public void insertCheckpoint(long planId, String checkpointCode, int seqNo) {
        jdbc.update("INSERT INTO plan_checkpoint (plan_id, checkpoint_code, seq_no) VALUES (?, ?, ?)",
                planId, checkpointCode, seqNo);
    }

    public List<String> findCheckpointCodes(long planId) {
        return jdbc.queryForList(
                "SELECT checkpoint_code FROM plan_checkpoint WHERE plan_id = ? ORDER BY seq_no",
                String.class, planId);
    }

    public Optional<RoundRow> findLatestRound(long planId) {
        return first(jdbc.query(
                "SELECT * FROM clearance_round WHERE plan_id = ? ORDER BY round_no DESC LIMIT 1",
                ROUND_MAPPER, planId));
    }

    public long insertRound(long planId, long roundNo, Instant openedAt) {
        return insert("INSERT INTO clearance_round (plan_id, round_no, status, opened_at)"
                        + " VALUES (?, ?, 'OPEN', ?)",
                planId, roundNo, Timestamp.from(openedAt));
    }

    public void updateRoundStatus(long roundId, String status, Instant closedAt) {
        jdbc.update("UPDATE clearance_round SET status = ?, closed_at = ? WHERE id = ?",
                status, closedAt == null ? null : Timestamp.from(closedAt), roundId);
    }

    public List<RoundRow> findRounds(long planId) {
        return jdbc.query("SELECT * FROM clearance_round WHERE plan_id = ? ORDER BY round_no",
                ROUND_MAPPER, planId);
    }
    public Optional<ConfirmationRow> findConfirmation(long roundId, String checkpointCode) {
        return first(jdbc.query(
                "SELECT * FROM checkpoint_confirmation WHERE round_id = ? AND checkpoint_code = ?",
                CONFIRMATION_MAPPER, roundId, checkpointCode));
    }

    public void insertConfirmation(long roundId, String checkpointCode, String confirmer,
                                   int headcount, Instant confirmedAt) {
        jdbc.update("INSERT INTO checkpoint_confirmation"
                        + " (round_id, checkpoint_code, confirmer, headcount, confirmed_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                roundId, checkpointCode, confirmer, headcount, Timestamp.from(confirmedAt));
    }

    public void updateConfirmation(long confirmationId, String confirmer, int headcount, Instant confirmedAt) {
        jdbc.update("UPDATE checkpoint_confirmation SET confirmer = ?, headcount = ?, confirmed_at = ?"
                        + " WHERE id = ?",
                confirmer, headcount, Timestamp.from(confirmedAt), confirmationId);
    }

    public List<ConfirmationRow> findConfirmations(long roundId) {
        return jdbc.query("SELECT * FROM checkpoint_confirmation WHERE round_id = ?"
                        + " ORDER BY checkpoint_code",
                CONFIRMATION_MAPPER, roundId);
    }

    public long insertPersonEntry(long planId, String reporter, Instant occurredAt, Instant recordedAt) {
        return insert("INSERT INTO person_entry_event (plan_id, reporter, occurred_at, recorded_at)"
                        + " VALUES (?, ?, ?, ?)",
                planId, reporter, Timestamp.from(occurredAt), Timestamp.from(recordedAt));
    }

    public List<PersonEntryRow> findPersonEntries(long planId) {
        return jdbc.query("SELECT * FROM person_entry_event WHERE plan_id = ? ORDER BY id",
                PERSON_ENTRY_MAPPER, planId);
    }

    public Optional<CommandRow> findCommandById(long commandId) {
        return first(jdbc.query("SELECT * FROM blast_command WHERE id = ?", COMMAND_MAPPER, commandId));
    }

    public Optional<CommandRow> findCommandByRequestId(String requestId) {
        return first(jdbc.query("SELECT * FROM blast_command WHERE request_id = ?",
                COMMAND_MAPPER, requestId));
    }

    public Optional<CommandRow> findValidCommand(long planId, Instant now) {
        return first(jdbc.query("SELECT * FROM blast_command"
                        + " WHERE plan_id = ? AND status = 'ISSUED' AND expires_at > ?",
                COMMAND_MAPPER, planId, Timestamp.from(now)));
    }

    public List<CommandRow> findCommands(long planId) {
        return jdbc.query("SELECT * FROM blast_command WHERE plan_id = ? ORDER BY id",
                COMMAND_MAPPER, planId);
    }

    public long insertCommand(long planId, long roundId, String requestId, String payloadHash,
                              String issuedBy, Instant issuedAt, Instant expiresAt) {
        return insert("INSERT INTO blast_command"
                        + " (plan_id, round_id, request_id, payload_hash, issued_by, issued_at, expires_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ISSUED')",
                planId, roundId, requestId, payloadHash, issuedBy,
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
    }

    public int invalidateIssuedCommands(long planId) {
        return jdbc.update("UPDATE blast_command SET status = 'INVALIDATED'"
                + " WHERE plan_id = ? AND status = 'ISSUED'", planId);
    }

    public void markCommandExecuted(long commandId, Instant executedAt) {
        jdbc.update("UPDATE blast_command SET status = 'EXECUTED', executed_at = ? WHERE id = ?",
                Timestamp.from(executedAt), commandId);
    }

    public Optional<ReportRow> findReportByRequestId(String requestId) {
        return first(jdbc.query("SELECT * FROM execution_report WHERE request_id = ?",
                REPORT_MAPPER, requestId));
    }

    public List<ReportRow> findReports(long planId) {
        return jdbc.query("SELECT r.* FROM execution_report r"
                        + " JOIN blast_command c ON c.id = r.command_id"
                        + " WHERE c.plan_id = ? ORDER BY r.id",
                REPORT_MAPPER, planId);
    }

    public long insertReport(long commandId, String requestId, String payloadHash,
                             String executor, String result, Instant reportedAt) {
        return insert("INSERT INTO execution_report"
                        + " (command_id, request_id, payload_hash, executor, result, reported_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                commandId, requestId, payloadHash, executor, result, Timestamp.from(reportedAt));
    }
}