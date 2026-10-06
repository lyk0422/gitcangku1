package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 择一前置组及成员的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行、再持有 task_graph_lock 全局锁的写事务内；
 * 配置替换在同一事务内先删旧组再写新组，任一环检测失败整体回滚、不留边。
 */
@Repository
public class TaskPrerequisiteRepository {

    private final JdbcTemplate jdbc;

    public TaskPrerequisiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskPrerequisiteGroup> GROUP_MAPPER = (rs, n) -> mapGroup(rs);

    private static TaskPrerequisiteGroup mapGroup(ResultSet rs) throws SQLException {
        long selected = rs.getLong("selected_member_id");
        return new TaskPrerequisiteGroup(rs.getLong("id"), rs.getLong("task_id"),
                rs.getInt("group_no"), rs.wasNull() ? null : selected,
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 成员明细行：联查成员任务的完整标识（事件号+任务号）与当前状态，用于视图组装、
     * 完成判定与冻结选中依据。按组序号、事件号、任务号字典序返回。
     */
    public record MemberDetail(long groupId, int groupNo, Long selectedMemberId, long memberId,
                               String incidentKey, String taskKey, String taskStatus,
                               int expectedTaskVersion) {
    }

    private static final RowMapper<MemberDetail> DETAIL_MAPPER = (rs, n) -> {
        long selected = rs.getLong("selected_member_id");
        return new MemberDetail(rs.getLong("group_id"), rs.getInt("group_no"),
                rs.wasNull() ? null : selected, rs.getLong("member_id"),
                rs.getString("incident_key"), rs.getString("task_key"), rs.getString("status"),
                rs.getInt("expected_task_version"));
    };

    /**
     * 删除任务的全部前置组及成员（配置替换的第一步，同事务回滚可恢复）。
     */
    public void deleteByTask(long taskId) {
        jdbc.update("DELETE FROM task_prerequisite_members WHERE group_id IN"
                + " (SELECT id FROM task_prerequisite_groups WHERE task_id = ?)", taskId);
        jdbc.update("DELETE FROM task_prerequisite_groups WHERE task_id = ?", taskId);
    }

    /**
     * 插入一个前置组，返回生成主键。(task_id, group_no) 唯一。
     */
    public long insertGroup(long taskId, int groupNo, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_prerequisite_groups (task_id, group_no, selected_member_id,"
                            + " created_at) VALUES (?,?,NULL,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId);
            ps.setInt(2, groupNo);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入一个前置组成员。(group_id, member_task_id) 唯一兜底组内重复。
     */
    public void insertMember(long groupId, long memberIncidentId, long memberTaskId,
                             int expectedTaskVersion, Instant now) {
        jdbc.update("INSERT INTO task_prerequisite_members (group_id, member_incident_id,"
                        + " member_task_id, expected_task_version, created_at) VALUES (?,?,?,?,?)",
                groupId, memberIncidentId, memberTaskId, expectedTaskVersion,
                Timestamp.from(now));
    }

    /**
     * 查询任务的全部前置组（按组序号），用于完成冻结与视图组装。
     */
    public List<TaskPrerequisiteGroup> listGroups(long taskId) {
        return jdbc.query("SELECT * FROM task_prerequisite_groups WHERE task_id = ?"
                + " ORDER BY group_no", GROUP_MAPPER, taskId);
    }

    /**
     * 联查任务全部前置组成员明细（含成员任务当前状态），按组序号、事件号、任务号排序。
     */
    public List<MemberDetail> listMemberDetails(long taskId) {
        return jdbc.query("SELECT g.id AS group_id, g.group_no, g.selected_member_id,"
                        + " m.id AS member_id, i.incident_key, t.task_key, t.status,"
                        + " m.expected_task_version"
                        + " FROM task_prerequisite_groups g"
                        + " JOIN task_prerequisite_members m ON m.group_id = g.id"
                        + " JOIN incident_tasks t ON t.id = m.member_task_id"
                        + " JOIN incidents i ON i.id = m.member_incident_id"
                        + " WHERE g.task_id = ?"
                        + " ORDER BY g.group_no, i.incident_key, t.task_key",
                DETAIL_MAPPER, taskId);
    }

    /**
     * 任务完成时冻结该组的选中依据成员，冻结后不再变更。
     */
    public void markSelected(long groupId, long memberId) {
        jdbc.update("UPDATE task_prerequisite_groups SET selected_member_id = ? WHERE id = ?",
                memberId, groupId);
    }
}
