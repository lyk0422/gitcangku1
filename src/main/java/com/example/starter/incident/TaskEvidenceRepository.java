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
 * 任务验收证据及必需证据类别的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内，证据确认、作废、必需类别变更
 * 与任务完成在同一事件行锁下串行判定。
 */
@Repository
public class TaskEvidenceRepository {

    private final JdbcTemplate jdbc;

    public TaskEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskEvidence> MAPPER = (rs, n) -> map(rs);

    private static TaskEvidence map(ResultSet rs) throws SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new TaskEvidence(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("evidence_key"),
                rs.getString("category"), rs.getString("note"),
                EvidenceStatus.valueOf(rs.getString("status")),
                rs.getString("uploaded_by"), rs.getTimestamp("uploaded_at").toInstant(),
                rs.getString("confirmed_by"),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                rs.getString("voided_by"),
                voidedAt == null ? null : voidedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 插入 UPLOADED 证据，返回生成主键。(task_id, evidence_key) 唯一约束兜底并发重复插入。
     */
    public long insert(TaskEvidence evidence) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_task_evidences (task_id, evidence_key, category, note,"
                            + " status, uploaded_by, uploaded_at, confirmed_by, confirmed_at,"
                            + " voided_by, voided_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, evidence.taskId());
            ps.setString(2, evidence.evidenceKey());
            ps.setString(3, evidence.category());
            ps.setString(4, evidence.note());
            ps.setString(5, evidence.status().name());
            ps.setString(6, evidence.uploadedBy());
            ps.setTimestamp(7, Timestamp.from(evidence.uploadedAt()));
            ps.setTimestamp(8, Timestamp.from(evidence.createdAt()));
            ps.setTimestamp(9, Timestamp.from(evidence.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务与证据编号查询，用于幂等比对与明细查询。
     */
    public Optional<TaskEvidence> findByKey(long taskId, String evidenceKey) {
        List<TaskEvidence> rows = jdbc.query(
                "SELECT * FROM incident_task_evidences WHERE task_id = ? AND evidence_key = ?",
                MAPPER, taskId, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务全部证据，按创建顺序返回。
     */
    public List<TaskEvidence> listByTask(long taskId) {
        return jdbc.query("SELECT * FROM incident_task_evidences WHERE task_id = ? ORDER BY id",
                MAPPER, taskId);
    }

    /**
     * 确认证据：仅 UPLOADED 可确认，记录确认人与 UTC 时刻。
     *
     * @return 更新行数；0 表示状态已被并发改变
     */
    public int confirm(long id, String confirmer, Instant at) {
        return jdbc.update("UPDATE incident_task_evidences SET status = 'CONFIRMED',"
                        + " confirmed_by = ?, confirmed_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'UPLOADED'",
                confirmer, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 作废证据：UPLOADED/CONFIRMED 可作废，作废使确认失效，记录作废人与 UTC 时刻。
     *
     * @return 更新行数；0 表示已是 VOID 终态
     */
    public int markVoid(long id, String actor, Instant at) {
        return jdbc.update("UPDATE incident_task_evidences SET status = 'VOID',"
                        + " voided_by = ?, voided_at = ?, updated_at = ?"
                        + " WHERE id = ? AND status <> 'VOID'",
                actor, Timestamp.from(at), Timestamp.from(at), id);
    }

    /**
     * 整体替换任务的必需证据类别：先清空再写入，同事务提交。
     */
    public void replaceRequirements(long taskId, List<String> categories, String setBy, Instant at) {
        jdbc.update("DELETE FROM incident_task_evidence_requirements WHERE task_id = ?", taskId);
        for (String category : categories) {
            jdbc.update("INSERT INTO incident_task_evidence_requirements"
                            + " (task_id, category, set_by, set_at) VALUES (?,?,?,?)",
                    taskId, category, setBy, Timestamp.from(at));
        }
    }

    /**
     * 查询任务当前必需证据类别，按类别名排序返回。
     */
    public List<String> listRequiredCategories(long taskId) {
        return jdbc.query("SELECT category FROM incident_task_evidence_requirements"
                        + " WHERE task_id = ? ORDER BY category",
                (rs, n) -> rs.getString(1), taskId);
    }

    /**
     * 查询任务当前已确认（CONFIRMED 且未作废）证据覆盖的类别。
     */
    public List<String> listConfirmedCategories(long taskId) {
        return jdbc.query("SELECT DISTINCT category FROM incident_task_evidences"
                        + " WHERE task_id = ? AND status = 'CONFIRMED'",
                (rs, n) -> rs.getString(1), taskId);
    }
}