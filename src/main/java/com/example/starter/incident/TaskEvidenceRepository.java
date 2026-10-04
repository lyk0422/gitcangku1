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
 * 所有写路径均处于先锁定事件行的写事务内，证据确认、作废、必需类别变更与任务完成
 * 相对彼此串行判定；(task_id, evidence_no) 与 (task_id, category) 唯一约束兜底并发。
 */
@Repository
public class TaskEvidenceRepository {

    private final JdbcTemplate jdbc;

    public TaskEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TaskEvidence> EVIDENCE_MAPPER = (rs, n) -> mapEvidence(rs);

    private static TaskEvidence mapEvidence(ResultSet rs) throws SQLException {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        Timestamp frozenAt = rs.getTimestamp("frozen_at");
        return new TaskEvidence(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("evidence_no"),
                rs.getString("category"), rs.getString("description"),
                EvidenceStatus.valueOf(rs.getString("status")),
                rs.getString("uploaded_by"), rs.getTimestamp("uploaded_at").toInstant(),
                rs.getString("confirmed_by"),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                rs.getString("voided_by"),
                voidedAt == null ? null : voidedAt.toInstant(),
                frozenAt == null ? null : frozenAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入 PENDING 证据，返回生成主键。(task_id, evidence_no) 唯一约束兜底并发重复插入。
     */
    public long insertEvidence(TaskEvidence evidence) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO task_evidence (task_id, evidence_no, category, description, status,"
                            + " uploaded_by, uploaded_at, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, evidence.taskId());
            ps.setString(2, evidence.evidenceNo());
            ps.setString(3, evidence.category());
            ps.setString(4, evidence.description());
            ps.setString(5, evidence.status().name());
            ps.setString(6, evidence.uploadedBy());
            ps.setTimestamp(7, Timestamp.from(evidence.uploadedAt()));
            ps.setTimestamp(8, Timestamp.from(evidence.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按任务与证据编号查询，用于幂等比对与明细查询。
     */
    public Optional<TaskEvidence> findEvidence(long taskId, String evidenceNo) {
        List<TaskEvidence> rows = jdbc.query(
                "SELECT * FROM task_evidence WHERE task_id = ? AND evidence_no = ?",
                EVIDENCE_MAPPER, taskId, evidenceNo);
        return rows.stream().findFirst();
    }

    /**
     * 查询任务全部证据，按上传顺序返回。
     */
    public List<TaskEvidence> listEvidence(long taskId) {
        return jdbc.query("SELECT * FROM task_evidence WHERE task_id = ? ORDER BY id",
                EVIDENCE_MAPPER, taskId);
    }

    /**
     * 将 PENDING 证据置为 CONFIRMED，记录确认人与 UTC 时刻；条件更新返回受影响行数。
     */
    public int confirmEvidence(long id, String confirmer, Instant at) {
        return jdbc.update("UPDATE task_evidence SET status = 'CONFIRMED', confirmed_by = ?,"
                        + " confirmed_at = ? WHERE id = ? AND status = 'PENDING'",
                confirmer, Timestamp.from(at), id);
    }

    /**
     * 将 PENDING/CONFIRMED 证据置为 VOIDED（作废使确认失效），记录作废人与 UTC 时刻。
     */
    public int voidEvidence(long id, String actor, Instant at) {
        return jdbc.update("UPDATE task_evidence SET status = 'VOIDED', voided_by = ?,"
                        + " voided_at = ? WHERE id = ? AND status IN ('PENDING','CONFIRMED')",
                actor, Timestamp.from(at), id);
    }

    /**
     * 任务完成时冻结该任务全部证据（写入冻结 UTC 时刻），此后禁止作废或覆盖。
     */
    public void freezeForTask(long taskId, Instant at) {
        jdbc.update("UPDATE task_evidence SET frozen_at = ? WHERE task_id = ? AND frozen_at IS NULL",
                Timestamp.from(at), taskId);
    }

    /**
     * 整体替换任务的必需证据类别（同事务内先删后插，调用方已持有事件行锁）。
     */
    public void replaceCategories(long taskId, List<String> categories, Instant now) {
        jdbc.update("DELETE FROM task_required_categories WHERE task_id = ?", taskId);
        for (String category : categories) {
            jdbc.update("INSERT INTO task_required_categories (task_id, category, created_at)"
                    + " VALUES (?,?,?)", taskId, category, Timestamp.from(now));
        }
    }

    /**
     * 查询任务当前必需证据类别，按类别名排序返回。
     */
    public List<String> listCategories(long taskId) {
        return jdbc.queryForList(
                "SELECT category FROM task_required_categories WHERE task_id = ? ORDER BY category",
                String.class, taskId);
    }

    /**
     * 查询缺少已确认且未作废证据的必需类别（完成门禁用），按类别名排序返回。
     */
    public List<String> listCategoriesMissingConfirmed(long taskId) {
        return jdbc.queryForList(
                "SELECT c.category FROM task_required_categories c WHERE c.task_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM task_evidence e"
                        + " WHERE e.task_id = c.task_id AND e.category = c.category"
                        + " AND e.status = 'CONFIRMED') ORDER BY c.category",
                String.class, taskId);
    }
}