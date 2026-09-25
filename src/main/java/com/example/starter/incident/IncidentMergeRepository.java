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
 * 重复事件合并记录及任务迁移明细仓储。合并记录不可变（仅插入）；
 * merge_key 与 merged_incident_id 唯一约束兜底并发重复合并。
 * 所有写调用均处于先锁定双方事件行的合并事务内。
 */
@Repository
public class IncidentMergeRepository {

    private final JdbcTemplate jdbc;

    public IncidentMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentMerge> MERGE_MAPPER = (rs, n) -> mapMerge(rs);

    private static IncidentMerge mapMerge(ResultSet rs) throws SQLException {
        return new IncidentMerge(rs.getLong("id"), rs.getString("merge_key"),
                rs.getLong("surviving_incident_id"), rs.getLong("merged_incident_id"),
                rs.getString("actor"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入合并记录，返回生成主键。唯一约束兜底并发同 mergeKey / 同被并入事件。
     */
    public long insert(IncidentMerge merge) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_merges (merge_key, surviving_incident_id,"
                            + " merged_incident_id, actor, created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, merge.mergeKey());
            ps.setLong(2, merge.survivingIncidentId());
            ps.setLong(3, merge.mergedIncidentId());
            ps.setString(4, merge.actor());
            ps.setTimestamp(5, Timestamp.from(merge.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按合并业务键查询（用于 mergeKey 唯一性预检）。
     */
    public Optional<IncidentMerge> findByKey(String mergeKey) {
        List<IncidentMerge> rows = jdbc.query("SELECT * FROM incident_merges WHERE merge_key = ?",
                MERGE_MAPPER, mergeKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部合并记录，按落库顺序（主键）稳定排序。
     */
    public List<IncidentMerge> listAll() {
        return jdbc.query("SELECT * FROM incident_merges ORDER BY id", MERGE_MAPPER);
    }

    /**
     * 记录一条任务迁移明细（合并事务内，随合并记录一并提交）。
     */
    public void insertMergeTask(long mergeId, long taskId, String taskKey, Instant now) {
        jdbc.update("INSERT INTO incident_merge_tasks (merge_id, task_id, task_key, created_at)"
                + " VALUES (?,?,?,?)", mergeId, taskId, taskKey, Timestamp.from(now));
    }

    /**
     * 查询某次合并迁移的任务键列表，按任务键稳定排序。
     */
    public List<String> listMergedTaskKeys(long mergeId) {
        return jdbc.query("SELECT task_key FROM incident_merge_tasks WHERE merge_id = ?"
                + " ORDER BY task_key", (rs, n) -> rs.getString("task_key"), mergeId);
    }

    /**
     * 合并后任务归属视图行：taskKey 为任务业务键，ownerIncidentKey 为任务当前所属事件键，
     * status 为任务当前状态。
     */
    public record TaskOwnershipRow(String taskKey, String ownerIncidentKey, String status) {
    }

    /**
     * 查询源自指定事件的全部任务当前归属：含仍留在本事件的任务与经合并迁出的任务，
     * 按 taskKey 稳定排序。任务当前归属以 incident_tasks.incident_id 实时计算，
     * 链式合并（A→B→C）下反映最终存续事件。
     */
    public List<TaskOwnershipRow> listTaskOwnership(long originIncidentId) {
        return jdbc.query("SELECT t.task_key, i.incident_key, t.status FROM incident_tasks t"
                        + " JOIN incidents i ON i.id = t.incident_id"
                        + " WHERE t.incident_id = ? OR t.id IN ("
                        + "   SELECT mt.task_id FROM incident_merge_tasks mt"
                        + "   JOIN incident_merges m ON m.id = mt.merge_id"
                        + "   WHERE m.merged_incident_id = ?)"
                        + " ORDER BY t.task_key",
                (rs, n) -> new TaskOwnershipRow(rs.getString(1), rs.getString(2), rs.getString(3)),
                originIncidentId, originIncidentId);
    }
}
