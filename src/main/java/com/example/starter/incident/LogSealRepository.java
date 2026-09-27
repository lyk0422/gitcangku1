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
 * 事件指挥日志封存仓储：封存包、包明细与更正附录均只插入不更新。
 * 版本号在事件行锁内分配，(incident_id, version) 唯一约束兜底并发重复。
 * 快照收集一律采用严格小于 cutoff_at 的左闭右开区间。
 */
@Repository
public class LogSealRepository {

    private final JdbcTemplate jdbc;

    public LogSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LogSeal> SEAL_MAPPER = (rs, n) -> new LogSeal(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
            rs.getTimestamp("cutoff_at").toInstant(), rs.getInt("record_count"),
            rs.getInt("edge_count"), rs.getString("content_hash"), rs.getString("sealed_by"),
            rs.getTimestamp("sealed_at").toInstant());

    private static final RowMapper<LogSealRecord> RECORD_MAPPER = (rs, n) -> mapRecord(rs);

    private static final RowMapper<LogCorrection> CORRECTION_MAPPER = (rs, n) -> new LogCorrection(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("seal_id"),
            rs.getInt("seal_version"), rs.getString("record_key"), rs.getString("field_name"),
            rs.getString("old_value"), rs.getString("new_value"), rs.getString("reason"),
            rs.getString("corrected_by"), rs.getTimestamp("created_at").toInstant());

    /** 依赖边快照行：任务及其阻塞事件（均取数字 id，归属校验由服务完成）。 */
    public record EdgeSnapshot(long taskId, long taskIncidentId, String taskKey,
                               long blockerIncidentId, Instant edgeCreatedAt) {
    }

    private static LogSealRecord mapRecord(ResultSet rs) throws SQLException {
        Long taskId = rs.getObject("task_id", Long.class);
        Long blockerIncidentId = rs.getObject("blocker_incident_id", Long.class);
        return new LogSealRecord(rs.getLong("id"), rs.getLong("seal_id"), rs.getInt("seq"),
                rs.getString("record_type"), rs.getString("record_key"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getInt("record_version"),
                taskId, blockerIncidentId, rs.getString("payload"));
    }

    /**
     * 插入封存包元数据，返回生成主键。
     */
    public long insertSeal(LogSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_seals (incident_id, version, cutoff_at, record_count,"
                            + " edge_count, content_hash, sealed_by, sealed_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.incidentId());
            ps.setInt(2, seal.version());
            ps.setTimestamp(3, Timestamp.from(seal.cutoffAt()));
            ps.setInt(4, seal.recordCount());
            ps.setInt(5, seal.edgeCount());
            ps.setString(6, seal.contentHash());
            ps.setString(7, seal.sealedBy());
            ps.setTimestamp(8, Timestamp.from(seal.sealedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入一条包明细（不可变快照）。
     */
    public void insertRecord(LogSealRecord record) {
        jdbc.update("INSERT INTO incident_log_seal_records (seal_id, seq, record_type, record_key,"
                        + " occurred_at, record_version, task_id, blocker_incident_id, payload)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                record.sealId(), record.seq(), record.recordType(), record.recordKey(),
                Timestamp.from(record.occurredAt()), record.recordVersion(),
                record.taskId(), record.blockerIncidentId(), record.payload());
    }

    /**
     * 按事件与版本查询封存包。
     */
    public Optional<LogSeal> findSeal(long incidentId, int version) {
        List<LogSeal> rows = jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? AND version = ?",
                SEAL_MAPPER, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 按事件与版本锁定封存包行（更正附录追加前持锁，与封存写入串行）。
     */
    public Optional<LogSeal> findSealForUpdate(long incidentId, int version) {
        List<LogSeal> rows = jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? AND version = ? FOR UPDATE",
                SEAL_MAPPER, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部封存包，按版本升序。
     */
    public List<LogSeal> listSeals(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? ORDER BY version",
                SEAL_MAPPER, incidentId);
    }

    /**
     * 查询封存包全部明细，按包内序号升序。
     */
    public List<LogSealRecord> listRecords(long sealId) {
        return jdbc.query(
                "SELECT * FROM incident_log_seal_records WHERE seal_id = ? ORDER BY seq",
                RECORD_MAPPER, sealId);
    }

    /**
     * 统计事件封存包数量（事件行锁内用于分配下一版本）。
     */
    public int countSeals(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seals WHERE incident_id = ?",
                Integer.class, incidentId);
        return count == null ? 0 : count;
    }

    /**
     * 追加一条更正附录（原包与明细不变）。
     */
    public long insertCorrection(LogCorrection correction) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_corrections (incident_id, seal_id, seal_version,"
                            + " record_key, field_name, old_value, new_value, reason,"
                            + " corrected_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, correction.incidentId());
            ps.setLong(2, correction.sealId());
            ps.setInt(3, correction.sealVersion());
            ps.setString(4, correction.recordKey());
            ps.setString(5, correction.fieldName());
            ps.setString(6, correction.oldValue());
            ps.setString(7, correction.newValue());
            ps.setString(8, correction.reason());
            ps.setString(9, correction.correctedBy());
            ps.setTimestamp(10, Timestamp.from(correction.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部更正附录，按追加顺序。
     */
    public List<LogCorrection> listCorrectionsByIncident(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_log_corrections WHERE incident_id = ? ORDER BY id",
                CORRECTION_MAPPER, incidentId);
    }

    /**
     * 查询指定封存包的更正附录，按追加顺序。
     */
    public List<LogCorrection> listCorrectionsBySeal(long sealId) {
        return jdbc.query(
                "SELECT * FROM incident_log_corrections WHERE seal_id = ? ORDER BY id",
                CORRECTION_MAPPER, sealId);
    }

    /**
     * 快照收集：发生时刻严格小于 cutoff 的指令（处置记录），按落库顺序。
     */
    public List<IncidentAction> actionsBefore(long incidentId, Instant cutoff) {
        return jdbc.query(
                "SELECT * FROM incident_actions WHERE incident_id = ? AND occurred_at < ? ORDER BY id",
                (rs, n) -> new IncidentAction(rs.getLong("id"), rs.getLong("incident_id"),
                        rs.getString("action_key"), rs.getString("action_type"), rs.getString("note"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant()),
                incidentId, Timestamp.from(cutoff));
    }

    /**
     * 快照收集：发起时刻严格小于 cutoff 的交接单（含仍 PENDING 的同刻边界单），按落库顺序。
     */
    public List<IncidentTransfer> transfersBefore(long incidentId, Instant cutoff) {
        return jdbc.query(
                "SELECT * FROM incident_transfers WHERE incident_id = ? AND created_at < ? ORDER BY id",
                (rs, n) -> {
                    Timestamp acceptedAt = rs.getTimestamp("accepted_at");
                    return new IncidentTransfer(rs.getLong("id"), rs.getLong("incident_id"),
                            rs.getString("from_commander"), rs.getString("to_commander"),
                            TransferStatus.valueOf(rs.getString("status")),
                            rs.getTimestamp("created_at").toInstant(),
                            acceptedAt == null ? null : acceptedAt.toInstant());
                },
                incidentId, Timestamp.from(cutoff));
    }

    /**
     * 快照收集：创建时刻严格小于 cutoff 的处置任务，按落库顺序。
     */
    public List<IncidentTask> tasksBefore(long incidentId, Instant cutoff) {
        return jdbc.query(
                "SELECT * FROM incident_tasks WHERE incident_id = ? AND created_at < ? ORDER BY id",
                (rs, n) -> IncidentTaskRepository.mapTask(rs),
                incidentId, Timestamp.from(cutoff));
    }

    /**
     * 快照收集：边创建时刻严格小于 cutoff、且起点任务属于该事件的任务依赖边。
     */
    public List<EdgeSnapshot> edgesBefore(long incidentId, Instant cutoff) {
        return jdbc.query(
                "SELECT t.id, t.incident_id, t.task_key, b.blocker_incident_id, b.created_at"
                        + " FROM incident_task_blockers b JOIN incident_tasks t ON t.id = b.task_id"
                        + " WHERE t.incident_id = ? AND b.created_at < ? ORDER BY b.id",
                (rs, n) -> new EdgeSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getLong(4), rs.getTimestamp(5).toInstant()),
                incidentId, Timestamp.from(cutoff));
    }

    /**
     * 同刻边界检测：返回发生时刻恰好等于 cutoff 的已提交指令标识（事件内排序）。
     * 调用方已持有事件行锁：未提交的同刻写入会先在锁上等待，提交后在此可见，
     * 回滚则不可见——按提交顺序裁决封存边界。
     */
    public List<String> findActionKeysAt(long incidentId, Instant cutoff) {
        return jdbc.queryForList(
                "SELECT action_key FROM incident_actions WHERE incident_id = ? AND occurred_at = ?"
                        + " ORDER BY action_key",
                String.class, incidentId, Timestamp.from(cutoff));
    }

    /**
     * 按事件 id 查询事件业务键（依赖边快照记录阻塞事件键用；事件不存在返回空）。
     */
    public Optional<String> findIncidentKey(long incidentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT incident_key FROM incidents WHERE id = ?", String.class, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 归属校验：任务 id 是否属于指定事件（封存前防御性校验，含事件合并等外部改图场景）。
     */
    public boolean taskBelongsToIncident(long taskId, long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE id = ? AND incident_id = ?",
                Integer.class, taskId, incidentId);
        return count != null && count > 0;
    }
}
