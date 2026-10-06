package com.example.starter.incident;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务择一前置组及完成快照的 JDBC 仓储。
 * 前置组：每任务 1~3 组，每组 2~5 个成员，成员以（事件 id, 任务 id）标识、可跨事件；
 * 配置替换整体删除重建。完成快照在任务完成时冻结组全集与每组选中的 DONE 依据，之后不修改。
 * 任务级依赖环检测与配置/完成/取消共用 task_graph_lock 全局锁串行化（由调用方持有）。
 */
@Repository
public class TaskPrereqRepository {

    private final JdbcTemplate jdbc;

    public TaskPrereqRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 前置成员视图行：含成员任务的当前状态（查询时实时计算，不写回）。
     */
    public record MemberRow(int groupNo, long memberIncidentId, long memberTaskId,
                            int expectedTaskVersion, String incidentKey, String taskKey,
                            String status) {
    }

    /**
     * 完成快照行：selected 标识该组选中的 DONE 依据。
     */
    public record SnapshotRow(int groupNo, String incidentKey, String taskKey, boolean selected) {
    }

    /**
     * 任务级依赖边：taskId（被配置任务）→ memberTaskId（前置成员任务）。
     */
    public record TaskEdge(long taskId, long memberTaskId) {
    }

    /**
     * 待写入的成员：成员事件 id、成员任务 id、配置时期望版本。
     */
    public record MemberSpec(long memberIncidentId, long memberTaskId, int expectedTaskVersion) {
    }

    private static final RowMapper<MemberRow> MEMBER_MAPPER = (rs, n) -> new MemberRow(
            rs.getInt("group_no"), rs.getLong("member_incident_id"),
            rs.getLong("member_task_id"), rs.getInt("expected_task_version"),
            rs.getString("incident_key"), rs.getString("task_key"), rs.getString("status"));

    /**
     * 查询任务当前全部前置组成员（按组序号、成员标识排序），含成员任务当前状态。
     */
    public List<MemberRow> listMembersByTask(long taskId) {
        return jdbc.query("SELECT g.group_no, m.member_incident_id, m.member_task_id,"
                        + " m.expected_task_version, i.incident_key, t.task_key, t.status"
                        + " FROM task_prereq_members m"
                        + " JOIN task_prereq_groups g ON g.id = m.group_id"
                        + " JOIN incidents i ON i.id = m.member_incident_id"
                        + " JOIN incident_tasks t ON t.id = m.member_task_id"
                        + " WHERE g.task_id = ? ORDER BY g.group_no, i.incident_key, t.task_key",
                MEMBER_MAPPER, taskId);
    }

    /**
     * 查询任务的完成快照（无快照返回空列表），按组序号、成员标识排序。
     */
    public List<SnapshotRow> listSnapshot(long taskId) {
        return jdbc.query("SELECT s.group_no, i.incident_key, t.task_key, s.selected"
                        + " FROM task_prereq_snapshot s"
                        + " JOIN incidents i ON i.id = s.member_incident_id"
                        + " JOIN incident_tasks t ON t.id = s.member_task_id"
                        + " WHERE s.task_id = ? ORDER BY s.group_no, i.incident_key, t.task_key",
                (rs, n) -> new SnapshotRow(rs.getInt("group_no"), rs.getString("incident_key"),
                        rs.getString("task_key"), rs.getBoolean("selected")),
                taskId);
    }

    /**
     * 整体替换任务的前置组：删除原有组及成员后按 groupNo（从 1 开始）写入新组。
     * 调用方须已持有 task_graph_lock 全局锁并完成环检测。
     */
    public void replaceGroups(long taskId, List<List<MemberSpec>> groups, Instant now) {
        jdbc.update("DELETE FROM task_prereq_members WHERE group_id IN"
                + " (SELECT id FROM task_prereq_groups WHERE task_id = ?)", taskId);
        jdbc.update("DELETE FROM task_prereq_groups WHERE task_id = ?", taskId);
        for (int g = 0; g < groups.size(); g++) {
            long groupId = insertGroup(taskId, g + 1, now);
            for (MemberSpec member : groups.get(g)) {
                jdbc.update("INSERT INTO task_prereq_members (group_id, member_incident_id,"
                                + " member_task_id, expected_task_version, created_at)"
                                + " VALUES (?,?,?,?,?)",
                        groupId, member.memberIncidentId(), member.memberTaskId(),
                        member.expectedTaskVersion(), Timestamp.from(now));
            }
        }
    }

    private long insertGroup(long taskId, int groupNo, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_prereq_groups (task_id, group_no, created_at) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId);
            ps.setInt(2, groupNo);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 写入完成快照：冻结每组全部成员及选中的 DONE 依据。仅任务完成时调用一次。
     */
    public void insertSnapshot(long taskId, int groupNo, long memberIncidentId, long memberTaskId,
                               boolean selected, Instant frozenAt) {
        jdbc.update("INSERT INTO task_prereq_snapshot (task_id, group_no, member_incident_id,"
                        + " member_task_id, selected, frozen_at) VALUES (?,?,?,?,?,?)",
                taskId, groupNo, memberIncidentId, memberTaskId, selected,
                Timestamp.from(frozenAt));
    }

    /**
     * 任务级环检测：在任务依赖图中判断从 fromTaskId 出发沿前置边是否可达 toTaskId。
     * 调用前必须已持有 task_graph_lock 全局锁，保证检测与后续组替换串行一致。
     */
    public boolean isReachableTask(long fromTaskId, long toTaskId) {
        if (fromTaskId == toTaskId) {
            return true;
        }
        List<TaskEdge> edges = jdbc.query(
                "SELECT g.task_id, m.member_task_id FROM task_prereq_members m"
                        + " JOIN task_prereq_groups g ON g.id = m.group_id",
                (rs, n) -> new TaskEdge(rs.getLong(1), rs.getLong(2)));
        Map<Long, List<Long>> adjacency = new HashMap<>();
        for (TaskEdge edge : edges) {
            adjacency.computeIfAbsent(edge.taskId(), k -> new ArrayList<>())
                    .add(edge.memberTaskId());
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(fromTaskId);
        visited.add(fromTaskId);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            for (Long next : adjacency.getOrDefault(current, List.of())) {
                if (next == toTaskId) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }
}
