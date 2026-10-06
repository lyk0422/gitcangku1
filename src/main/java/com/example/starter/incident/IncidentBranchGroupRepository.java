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
 * 互斥处置分支组及成员的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；(incident_id, group_key) 与
 * 成员表 task_id 的唯一约束兜底并发重复写入；选择以版本条件更新做乐观裁决。
 */
@Repository
public class IncidentBranchGroupRepository {

    private final JdbcTemplate jdbc;

    public IncidentBranchGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentTaskBranchGroup> GROUP_MAPPER = (rs, n) -> mapGroup(rs);

    private static IncidentTaskBranchGroup mapGroup(ResultSet rs) throws SQLException {
        long chosenTaskId = rs.getLong("chosen_task_id");
        Timestamp chosenAt = rs.getTimestamp("chosen_at");
        return new IncidentTaskBranchGroup(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("group_key"),
                BranchGroupStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.wasNull() ? null : chosenTaskId,
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("chosen_by"),
                chosenAt == null ? null : chosenAt.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 OPEN 分支组（版本 1），返回生成主键。
     * (incident_id, group_key) 唯一约束兜底并发重复插入。
     */
    public long insert(IncidentTaskBranchGroup group) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_task_branch_groups (incident_id, group_key, status,"
                            + " version, chosen_task_id, created_by, created_at, chosen_by,"
                            + " chosen_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, group.incidentId());
            ps.setString(2, group.groupKey());
            ps.setString(3, group.status().name());
            ps.setLong(4, group.version());
            ps.setObject(5, group.chosenTaskId());
            ps.setString(6, group.createdBy());
            ps.setTimestamp(7, Timestamp.from(group.createdAt()));
            ps.setString(8, group.chosenBy());
            ps.setTimestamp(9, group.chosenAt() == null ? null : Timestamp.from(group.chosenAt()));
            ps.setTimestamp(10, Timestamp.from(group.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 groupKey 查询分支组。
     */
    public Optional<IncidentTaskBranchGroup> findByKey(long incidentId, String groupKey) {
        List<IncidentTaskBranchGroup> rows = jdbc.query(
                "SELECT * FROM incident_task_branch_groups WHERE incident_id = ? AND group_key = ?",
                GROUP_MAPPER, incidentId, groupKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部分支组，按创建顺序返回。
     */
    public List<IncidentTaskBranchGroup> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_task_branch_groups WHERE incident_id = ?"
                + " ORDER BY id", GROUP_MAPPER, incidentId);
    }

    /**
     * 查询任务所属分支组（每任务至多一组），无则空。
     */
    public Optional<IncidentTaskBranchGroup> findGroupByTask(long taskId) {
        List<IncidentTaskBranchGroup> rows = jdbc.query(
                "SELECT g.* FROM incident_task_branch_groups g"
                        + " JOIN incident_task_branch_members m ON m.group_id = g.id"
                        + " WHERE m.task_id = ?",
                GROUP_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    /**
     * 追加组成员。task_id 唯一约束兜底"每任务至多一组"。
     */
    public void insertMember(long groupId, long taskId, Instant now) {
        jdbc.update("INSERT INTO incident_task_branch_members (group_id, task_id, created_at)"
                + " VALUES (?,?,?)", groupId, taskId, Timestamp.from(now));
    }

    /**
     * 查询组成员任务 id，按加入顺序返回。
     */
    public List<Long> listMemberTaskIds(long groupId) {
        return jdbc.query("SELECT task_id FROM incident_task_branch_members WHERE group_id = ?"
                + " ORDER BY id", (rs, n) -> rs.getLong(1), groupId);
    }

    /**
     * 乐观选择：仅当组仍为 OPEN 且版本等于 expectedVersion 时置 CHOSEN、版本加一，
     * 返回更新行数（0 表示已被并发选择或版本不匹配）。
     */
    public int markChosen(long groupId, long expectedVersion, long chosenTaskId,
                          String actor, Instant at) {
        return jdbc.update("UPDATE incident_task_branch_groups SET status = 'CHOSEN',"
                        + " version = version + 1, chosen_task_id = ?, chosen_by = ?,"
                        + " chosen_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                chosenTaskId, actor, Timestamp.from(at), Timestamp.from(at),
                groupId, expectedVersion);
    }
}
