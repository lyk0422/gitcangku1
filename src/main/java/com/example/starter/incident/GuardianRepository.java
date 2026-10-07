package com.example.starter.incident;

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
 * 双人监护配置与限时在场凭证的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；凭证代次的推进、撤销、冻结均采用
 * 条件 UPDATE（仅作用于 ACTIVE），由任务行所属事件的行锁串行化，保证完成与
 * 签到/撤销/取消在同一状态与凭证边界下互斥，不会在一方已失效后完成。
 */
@Repository
public class GuardianRepository {

    private final JdbcTemplate jdbc;

    public GuardianRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskGuardian> GUARDIAN_MAPPER = (rs, n) -> new TaskGuardian(
            rs.getLong("id"), rs.getLong("task_id"), rs.getString("guardian_one"),
            rs.getString("guardian_two"), rs.getInt("version"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<TaskAttendance> ATTENDANCE_MAPPER = (rs, n) -> mapAttendance(rs);

    private static TaskAttendance mapAttendance(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new TaskAttendance(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("guardian"),
                rs.getInt("generation"), rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                AttendanceStatus.valueOf(rs.getString("status")),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入监护配置（version 从 1 开始），返回生成主键。task_id 唯一约束兜底并发重复启用。
     */
    public long insertGuardian(TaskGuardian guardian) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_guardians (task_id, guardian_one, guardian_two, version,"
                            + " created_by, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, guardian.taskId());
            ps.setString(2, guardian.guardianOne());
            ps.setString(3, guardian.guardianTwo());
            ps.setInt(4, guardian.version());
            ps.setString(5, guardian.createdBy());
            ps.setTimestamp(6, Timestamp.from(guardian.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务查询监护配置，无则空。
     */
    public Optional<TaskGuardian> findByTaskId(long taskId) {
        List<TaskGuardian> rows = jdbc.query(
                "SELECT * FROM task_guardians WHERE task_id = ?", GUARDIAN_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 插入一条签到凭证（代次由调用方确定，从 1 开始递增），返回生成主键。
     * (task_id, guardian, generation) 唯一约束兜底并发重复代次。
     */
    public long insertAttendance(TaskAttendance attendance) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_attendance (task_id, guardian, generation, issued_at,"
                            + " expires_at, status, revoked_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, attendance.taskId());
            ps.setString(2, attendance.guardian());
            ps.setInt(3, attendance.generation());
            ps.setTimestamp(4, Timestamp.from(attendance.issuedAt()));
            ps.setTimestamp(5, Timestamp.from(attendance.expiresAt()));
            ps.setString(6, attendance.status().name());
            ps.setTimestamp(7, attendance.revokedAt() == null
                    ? null : Timestamp.from(attendance.revokedAt()));
            ps.setTimestamp(8, Timestamp.from(attendance.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询某监护人当前（最大）代次；从未签到时为空。
     */
    public Optional<TaskAttendance> findLatest(long taskId, String guardian) {
        List<TaskAttendance> rows = jdbc.query(
                "SELECT * FROM task_attendance WHERE task_id = ? AND guardian = ?"
                        + " ORDER BY generation DESC LIMIT 1",
                ATTENDANCE_MAPPER, taskId, guardian);
        return rows.stream().findFirst();
    }

    /**
     * 锁定读某监护人指定代次凭证，用于完成时在事务边界内核对状态与有效期。
     */
    public Optional<TaskAttendance> findGenerationForUpdate(long taskId, String guardian,
                                                            int generation) {
        List<TaskAttendance> rows = jdbc.query(
                "SELECT * FROM task_attendance WHERE task_id = ? AND guardian = ? AND generation = ?"
                        + " FOR UPDATE",
                ATTENDANCE_MAPPER, taskId, guardian, generation);
        return rows.stream().findFirst();
    }

    /**
     * 锁定读任务的全部凭证，按监护人、代次升序，用于撤销/完成时的边界核对与证据组装。
     */
    public List<TaskAttendance> listForUpdate(long taskId) {
        return jdbc.query(
                "SELECT * FROM task_attendance WHERE task_id = ?"
                        + " ORDER BY guardian, generation FOR UPDATE",
                ATTENDANCE_MAPPER, taskId);
    }

    /**
     * 普通读任务的全部凭证，按监护人、代次升序（只读查询用）。
     */
    public List<TaskAttendance> listByTaskId(long taskId) {
        return jdbc.query(
                "SELECT * FROM task_attendance WHERE task_id = ? ORDER BY guardian, generation",
                ATTENDANCE_MAPPER, taskId);
    }

    /**
     * 将某监护人当前 ACTIVE 凭证置为 SUPERSEDED（重新签到推进代次）。
     * 仅 ACTIVE 行受影响；已 REVOKED/FROZEN/SUPERSEDED 的历史代次保持不变。
     *
     * @return 实际更新行数
     */
    public int supersedeActive(long taskId, String guardian) {
        return jdbc.update(
                "UPDATE task_attendance SET status = 'SUPERSEDED' WHERE task_id = ? AND guardian = ?"
                        + " AND status = 'ACTIVE'",
                taskId, guardian);
    }

    /**
     * 撤销某监护人当前 ACTIVE 凭证，记录撤销 UTC 时刻；非 ACTIVE 撤销返回 0 行。
     *
     * @return 实际更新行数
     */
    public int revokeActive(long taskId, String guardian, Instant revokedAt) {
        return jdbc.update(
                "UPDATE task_attendance SET status = 'REVOKED', revoked_at = ?"
                        + " WHERE task_id = ? AND guardian = ? AND status = 'ACTIVE'",
                Timestamp.from(revokedAt), taskId, guardian);
    }

    /**
     * 完成时将指定代次凭证冻结（保留原签发/到期时刻）。仅 ACTIVE 行受影响，
     * 并发撤销/重新签到若先生效则更新 0 行，完成据此判定凭证边界已被破坏。
     *
     * @return 实际更新行数
     */
    public int freezeGeneration(long taskId, String guardian, int generation) {
        return jdbc.update(
                "UPDATE task_attendance SET status = 'FROZEN' WHERE task_id = ? AND guardian = ?"
                        + " AND generation = ? AND status = 'ACTIVE'",
                taskId, guardian, generation);
    }
}
