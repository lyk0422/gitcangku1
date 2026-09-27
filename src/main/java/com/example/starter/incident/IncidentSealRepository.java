package com.example.starter.incident;

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
 * 日志封存包、封存记录、固化依赖边与更正附录的 JDBC 仓储。
 * 封存写路径处于先锁定事件行（并持有任务依赖图全局锁）的写事务内，
 * 与指令写入、交接、任务依赖修订按事务提交顺序裁决；
 * 封存包与记录、边只插入不更新，事实错误只能通过 incident_log_appendices 追加更正。
 */
@Repository
public class IncidentSealRepository {

    private final JdbcTemplate jdbc;

    public IncidentSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentLogSeal> SEAL_MAPPER = (rs, n) -> new IncidentLogSeal(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
            rs.getTimestamp("seal_until").toInstant(), rs.getTimestamp("sealed_at").toInstant(),
            rs.getString("sealed_by"), rs.getInt("record_count"), rs.getInt("edge_count"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SealRecord> RECORD_MAPPER = (rs, n) -> new SealRecord(
            rs.getLong("id"), rs.getLong("seal_id"),
            SealRecordType.valueOf(rs.getString("record_type")), rs.getString("record_key"),
            rs.getInt("record_version"), rs.getTimestamp("occurred_at").toInstant(),
            rs.getInt("seq"), rs.getString("payload"));

    private static final RowMapper<SealEdge> EDGE_MAPPER = (rs, n) -> new SealEdge(
            rs.getLong("id"), rs.getLong("seal_id"), rs.getString("task_key"),
            rs.getString("blocker_incident_key"), rs.getInt("seq"));

    private static final RowMapper<SealAppendix> APPENDIX_MAPPER = (rs, n) -> new SealAppendix(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("seal_id"),
            rs.getInt("seal_version"), rs.getString("record_key"), rs.getString("field_name"),
            rs.getString("old_value"), rs.getString("new_value"), rs.getString("reason"),
            rs.getString("operator"), rs.getTimestamp("created_at").toInstant());

    /**
     * 待封装的依赖边快照行：本事件任务 → 阻塞事件。
     */
    public record EdgeSnapshot(long taskId, String taskKey, long blockerIncidentId,
                               String blockerIncidentKey) {
    }

    /**
     * 插入封存包头，返回生成主键。(incident_id, version) 唯一约束兜底并发重复插入。
     */
    public long insertSeal(IncidentLogSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_seals (incident_id, version, seal_until, sealed_at,"
                            + " sealed_by, record_count, edge_count, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.incidentId());
            ps.setInt(2, seal.version());
            ps.setTimestamp(3, Timestamp.from(seal.sealUntil()));
            ps.setTimestamp(4, Timestamp.from(seal.sealedAt()));
            ps.setString(5, seal.sealedBy());
            ps.setInt(6, seal.recordCount());
            ps.setInt(7, seal.edgeCount());
            ps.setTimestamp(8, Timestamp.from(seal.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件当前最大封存包版本，无封存包时为空。
     */
    public Optional<Integer> maxVersion(long incidentId) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM incident_log_seals WHERE incident_id = ?",
                Integer.class, incidentId);
        return Optional.ofNullable(max);
    }

    /**
     * 按事件与版本查询封存包头。
     */
    public Optional<IncidentLogSeal> findSeal(long incidentId, int version) {
        List<IncidentLogSeal> rows = jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? AND version = ?",
                SEAL_MAPPER, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部封存包头，按版本升序返回。
     */
    public List<IncidentLogSeal> listSeals(long incidentId) {
        return jdbc.query("SELECT * FROM incident_log_seals WHERE incident_id = ? ORDER BY version",
                SEAL_MAPPER, incidentId);
    }

    /**
     * 插入一条封存记录（不可变）。
     */
    public void insertRecord(SealRecord record) {
        jdbc.update("INSERT INTO incident_log_seal_records (seal_id, record_type, record_key,"
                        + " record_version, occurred_at, seq, payload) VALUES (?,?,?,?,?,?,?)",
                record.sealId(), record.recordType().name(), record.recordKey(),
                record.recordVersion(), Timestamp.from(record.occurredAt()), record.seq(),
                record.payload());
    }

    /**
     * 插入一条固化依赖边（不可变）。
     */
    public void insertEdge(SealEdge edge) {
        jdbc.update("INSERT INTO incident_log_seal_edges (seal_id, task_key, blocker_incident_key,"
                        + " seq) VALUES (?,?,?,?)",
                edge.sealId(), edge.taskKey(), edge.blockerIncidentKey(), edge.seq());
    }

    /**
     * 查询封存包全部记录，按包内序号（occurred_at, record_key 升序）返回。
     */
    public List<SealRecord> listRecords(long sealId) {
        return jdbc.query("SELECT * FROM incident_log_seal_records WHERE seal_id = ? ORDER BY seq",
                RECORD_MAPPER, sealId);
    }

    /**
     * 按原记录标识查询封存包内记录（跨类型取首条，按类型字典序保证确定性）。
     */
    public Optional<SealRecord> findRecord(long sealId, String recordKey) {
        List<SealRecord> rows = jdbc.query(
                "SELECT * FROM incident_log_seal_records WHERE seal_id = ? AND record_key = ?"
                        + " ORDER BY record_type",
                RECORD_MAPPER, sealId, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询封存包全部固化依赖边，按包内序号返回。
     */
    public List<SealEdge> listEdges(long sealId) {
        return jdbc.query("SELECT * FROM incident_log_seal_edges WHERE seal_id = ? ORDER BY seq",
                EDGE_MAPPER, sealId);
    }

    /**
     * 追加一条更正附录（只追加，不修改原包），返回生成主键。
     */
    public long insertAppendix(SealAppendix appendix) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_appendices (incident_id, seal_id, seal_version,"
                            + " record_key, field_name, old_value, new_value, reason, operator,"
                            + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, appendix.incidentId());
            ps.setLong(2, appendix.sealId());
            ps.setInt(3, appendix.sealVersion());
            ps.setString(4, appendix.recordKey());
            ps.setString(5, appendix.fieldName());
            ps.setString(6, appendix.oldValue());
            ps.setString(7, appendix.newValue());
            ps.setString(8, appendix.reason());
            ps.setString(9, appendix.operator());
            ps.setTimestamp(10, Timestamp.from(appendix.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件全部更正附录，按追加顺序返回。
     */
    public List<SealAppendix> listAppendices(long incidentId) {
        return jdbc.query("SELECT * FROM incident_log_appendices WHERE incident_id = ? ORDER BY id",
                APPENDIX_MAPPER, incidentId);
    }

    /**
     * 查询指定封存包的全部更正附录，按追加顺序返回。
     */
    public List<SealAppendix> listAppendicesBySeal(long sealId) {
        return jdbc.query("SELECT * FROM incident_log_appendices WHERE seal_id = ? ORDER BY id",
                APPENDIX_MAPPER, sealId);
    }

    /**
     * 统计指定时刻已提交但尚未补写响应的指令写入数（同刻未完成写入，用于封存 409 判定）。
     * 排除当前封存请求自身的幂等占位行。
     */
    public int countInFlightCommandsAt(Instant at, String excludeCommandKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE response_body IS NULL AND created_at = ?"
                        + " AND command_key <> ?",
                Integer.class, Timestamp.from(at), excludeCommandKey);
        return count == null ? 0 : count;
    }

    /**
     * 查询事件任务在指定时刻前创建的依赖边快照（含阻塞事件键），按任务键、阻塞事件键排序。
     */
    public List<EdgeSnapshot> listEdgeSnapshotsBefore(long incidentId, Instant until) {
        return jdbc.query(
                "SELECT t.id AS task_id, t.task_key, b.blocker_incident_id, i.incident_key"
                        + " FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id"
                        + " JOIN incidents i ON i.id = b.blocker_incident_id"
                        + " WHERE t.incident_id = ? AND b.created_at < ?"
                        + " ORDER BY t.task_key, i.incident_key",
                (rs, n) -> new EdgeSnapshot(rs.getLong("task_id"), rs.getString("task_key"),
                        rs.getLong("blocker_incident_id"), rs.getString("incident_key")),
                incidentId, Timestamp.from(until));
    }

    /**
     * 统计事件全部封存包的记录总数与依赖边总数（诊断用）。
     */
    public long[] sumPackageContents(long incidentId) {
        return jdbc.query("SELECT COALESCE(SUM(record_count),0) AS rc,"
                        + " COALESCE(SUM(edge_count),0) AS ec FROM incident_log_seals"
                        + " WHERE incident_id = ?",
                (rs, n) -> new long[]{rs.getLong("rc"), rs.getLong("ec")}, incidentId)
                .stream().findFirst().orElse(new long[]{0L, 0L});
    }

    /**
     * 统计事件更正附录总数（诊断用）。
     */
    public long countAppendices(long incidentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_appendices WHERE incident_id = ?",
                Long.class, incidentId);
        return count == null ? 0L : count;
    }
}
