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
 * 互斥分支组及成员的 JDBC 仓储。
 * (incident_id, group_key) 唯一；members.task_id 唯一约束保证每任务最多属一组。
 * 所有写路径均处于先锁定事件行的写事务内；选择以条件 UPDATE 按状态与版本乐观兜底，
 * 并发争选只有一份提交生效。
 */
@Repository
public class IncidentBranchGroupRepository {

    private final JdbcTemplate jdbc;

    public IncidentBranchGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentBranchGroup> GROUP_MAPPER = (rs, n) -> mapGroup(rs);

    private static IncidentBranchGroup mapGroup(ResultSet rs) throws SQLException {
        Timestamp chosenAt = rs.getTimestamp("chosen_at");
        long chosenTaskId = rs.getLong("chosen_task_id");
        return new IncidentBranchGroup(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("group_key"),
                BranchGroupStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.wasNull() ? null : chosenTaskId, rs.getString("chosen_by"),
                chosenAt == null ? null : chosenAt.toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final RowMapper<IncidentTask> TASK_MAPPER = (rs, n) -> mapTask(rs);

    private static IncidentTask mapTask(ResultSet rs) throws SQLException {
        Timestamp doneAt = rs.getTimestamp("done_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new IncidentTask(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
                rs.getString("group_code"), rs.getString("title"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getString("done_by"),
                doneAt == null ? null : doneAt.toInstant(),
                rs.getString("cancelled_by"),
                cancelledAt == null ? null : cancelledAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 OPEN 分支组（版本初始 1），返回生成主键。
     * (incident_id, group_key) 唯一约束兜底并发重复插入。
     */
    public long insert(IncidentBranchGroup group) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_branch_groups (incident_id, group_key, status, version,"
                            + " chosen_task_id, chosen_by, chosen_at, created_by, created_at,"
                            + " updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, group.incidentId());
            ps.setString(2, group.groupKey());
            ps.setString(3, group.status().name());
            ps.setLong(4, group.version());
            ps.setObject(5, group.chosenTaskId());
            ps.setString(6, group.chosenBy());
            ps.setTimestamp(7, group.chosenAt() == null ? null : Timestamp.from(group.chosenAt()));
            ps.setString(8, group.createdBy());
            ps.setTimestamp(9, Timestamp.from(group.createdAt()));
            ps.setTimestamp(10, Timestamp.from(group.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 追加一名组成员。members.task_id 唯一约束兜底每任务最多属一组。
     */
    public void insertMember(long groupId, long taskId, Instant now) {
        jdbc.update("INSERT INTO incident_branch_group_members (group_id, task_id, created_at)"
                + " VALUES (?,?,?)", groupId, taskId, Timestamp.from(now));
    }

    /**
     * 按事件与 groupKey 查询分支组，用于幂等比对与选择入口。
     */
    public Optional<IncidentBranchGroup> findByKey(long incidentId, String groupKey) {
        List<IncidentBranchGroup> rows = jdbc.query(
                "SELECT * FROM incident_branch_groups WHERE incident_id = ? AND group_key = ?",
                GROUP_MAPPER, incidentId, groupKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部分支组，按创建顺序返回。
     */
    public List<IncidentBranchGroup> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_branch_groups WHERE incident_id = ? ORDER BY id",
                GROUP_MAPPER, incidentId);
    }

    /**
     * 查询任务所属的分支组（每任务最多属一组），用于拦截普通完成/取消入口。
     */
    public Optional<IncidentBranchGroup> findGroupByTaskId(long taskId) {
        List<IncidentBranchGroup> rows = jdbc.query(
                "SELECT g.* FROM incident_branch_groups g"
                        + " JOIN incident_branch_group_members m ON m.group_id = g.id"
                        + " WHERE m.task_id = ?",
                GROUP_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 查询组的全部成员任务（含当前状态），按任务创建顺序返回。
     */
    public List<IncidentTask> listMemberTasks(long groupId) {
        return jdbc.query("SELECT t.* FROM incident_tasks t"
                        + " JOIN incident_branch_group_members m ON m.task_id = t.id"
                        + " WHERE m.group_id = ? ORDER BY t.id",
                TASK_MAPPER, groupId);
    }

    /**
     * 将 OPEN 组按期望版本原子置为 CHOSEN 并版本加一，记录选中成员、操作人与 UTC 时刻。
     *
     * @return 影响行数；0 表示组已被并发选择或版本已变化
     */
    public int markChosen(long id, long expectedVersion, long chosenTaskId, String actor,
                          Instant at) {
        return jdbc.update("UPDATE incident_branch_groups SET status = 'CHOSEN',"
                        + " version = version + 1, chosen_task_id = ?, chosen_by = ?,"
                        + " chosen_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                chosenTaskId, actor, Timestamp.from(at), Timestamp.from(at), id, expectedVersion);
    }
}
