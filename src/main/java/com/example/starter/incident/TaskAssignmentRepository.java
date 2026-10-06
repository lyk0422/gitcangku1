package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务执行安排的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；创建/替换安排额外持有 assignment_lock 单行锁，
 * 使跨事件的人员重叠、休息与日上限校验相对其他安排串行化。
 * 每任务至多一条当前安排（task_id 唯一）：替换即更新本行；
 * 未开始任务取消时删除本行释放时段，已开始任务的完成/取消保留本行。
 */
@Repository
public class TaskAssignmentRepository {

    /** 人员安排全局锁行的固定主键。 */
    private static final long LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public TaskAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskAssignment> MAPPER = (rs, n) -> map(rs);

    private static TaskAssignment map(ResultSet rs) throws SQLException {
        return new TaskAssignment(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("assignee"),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入安排，返回生成主键。task_id 唯一约束兜底并发重复插入。
     */
    public long insert(TaskAssignment assignment) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_assignments (task_id, assignee, start_at, end_at, created_by,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, assignment.taskId());
            ps.setString(2, assignment.assignee());
            ps.setTimestamp(3, Timestamp.from(assignment.startAt()));
            ps.setTimestamp(4, Timestamp.from(assignment.endAt()));
            ps.setString(5, assignment.createdBy());
            ps.setTimestamp(6, Timestamp.from(assignment.createdAt()));
            ps.setTimestamp(7, Timestamp.from(assignment.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 替换既有安排的人员与时段（任务仍 OPEN 时）。
     */
    public void update(long id, String assignee, Instant startAt, Instant endAt, Instant updatedAt) {
        jdbc.update("UPDATE task_assignments SET assignee = ?, start_at = ?, end_at = ?,"
                        + " updated_at = ? WHERE id = ?",
                assignee, Timestamp.from(startAt), Timestamp.from(endAt),
                Timestamp.from(updatedAt), id);
    }

    /**
     * 删除任务的安排（未开始任务取消或撤销安排时释放时段）。
     */
    public void deleteByTaskId(long taskId) {
        jdbc.update("DELETE FROM task_assignments WHERE task_id = ?", taskId);
    }

    /**
     * 按任务查询当前安排。
     */
    public Optional<TaskAssignment> findByTaskId(long taskId) {
        List<TaskAssignment> rows = jdbc.query(
                "SELECT * FROM task_assignments WHERE task_id = ?", MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询人员的全部安排（跨事件），按开始时刻升序，用于重叠、休息与日上限校验。
     */
    public List<TaskAssignment> listByAssignee(String assignee) {
        return jdbc.query("SELECT * FROM task_assignments WHERE assignee = ? ORDER BY start_at, id",
                MAPPER, assignee);
    }

    /**
     * 持有人员安排全局锁（单行 SELECT ... FOR UPDATE），串行化校验与写入。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockAssignments() {
        try {
            jdbc.update("INSERT INTO assignment_lock (id) VALUES (?)", LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM assignment_lock WHERE id = ? FOR UPDATE",
                Long.class, LOCK_ID);
    }
}
