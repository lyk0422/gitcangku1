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
 * 指挥日志封存（封存包、条目、依赖边快照、更正附录）的 JDBC 仓储。
 * 封存表只增不改：不提供任何 UPDATE 路径，保证已固化内容不被后续业务操作改写。
 * 所有写路径均处于先锁定事件行的写事务内，与指令写入、交接、任务修订按事务提交顺序裁决。
 */
@Repository
public class SealRepository {

    private final JdbcTemplate jdbc;

    public SealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 事件任务依赖边行（含阻塞事件 id 与业务键），用于封存时快照与环校验。
     */
    public record EdgeRow(String taskKey, long blockerIncidentId, String blockerIncidentKey,
                          Instant edgeCreatedAt) {
    }

    private static final RowMapper<IncidentSealPackage> PACKAGE_MAPPER = (rs, n) -> new IncidentSealPackage(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
            rs.getTimestamp("lower_bound_at").toInstant(), rs.getTimestamp("cutoff_at").toInstant(),
            rs.getInt("entry_count"), rs.getInt("edge_count"), rs.getString("sealed_by"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<IncidentSealEntry> ENTRY_MAPPER = (rs, n) -> new IncidentSealEntry(
            rs.getLong("id"), rs.getLong("package_id"), rs.getInt("seq_no"),
            rs.getString("record_type"), rs.getString("record_key"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getInt("record_version"),
            rs.getString("payload"));

    private static final RowMapper<IncidentSealEdge> EDGE_MAPPER = (rs, n) -> new IncidentSealEdge(
            rs.getLong("id"), rs.getLong("package_id"), rs.getString("task_key"),
            rs.getString("blocker_incident_key"), rs.getTimestamp("edge_created_at").toInstant());

    private static final RowMapper<IncidentSealAppendix> APPENDIX_MAPPER = (rs, n) -> new IncidentSealAppendix(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("package_id"),
            rs.getInt("package_version"), rs.getString("record_key"),
            rs.getString("old_value"), rs.getString("new_value"), rs.getString("reason"),
            rs.getString("actor"), rs.getTimestamp("created_at").toInstant());

    /**
     * 插入封存包，返回生成主键。(incident_id, version) 唯一约束兜底并发重复版本。
     */
    public long insertPackage(IncidentSealPackage pkg) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_seal_packages (incident_id, version, lower_bound_at,"
                            + " cutoff_at, entry_count, edge_count, sealed_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, pkg.incidentId());
            ps.setInt(2, pkg.version());
            ps.setTimestamp(3, Timestamp.from(pkg.lowerBoundAt()));
            ps.setTimestamp(4, Timestamp.from(pkg.cutoffAt()));
            ps.setInt(5, pkg.entryCount());
            ps.setInt(6, pkg.edgeCount());
            ps.setString(7, pkg.sealedBy());
            ps.setTimestamp(8, Timestamp.from(pkg.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 追加封存条目（只增不改）。
     */
    public void insertEntry(IncidentSealEntry entry) {
        jdbc.update("INSERT INTO incident_seal_entries (package_id, seq_no, record_type, record_key,"
                        + " occurred_at, record_version, payload) VALUES (?,?,?,?,?,?,?)",
                entry.packageId(), entry.seqNo(), entry.recordType(), entry.recordKey(),
                Timestamp.from(entry.occurredAt()), entry.recordVersion(), entry.payload());
    }

    /**
     * 追加依赖边快照（只增不改）。
     */
    public void insertEdge(IncidentSealEdge edge) {
        jdbc.update("INSERT INTO incident_seal_edges (package_id, task_key, blocker_incident_key,"
                        + " edge_created_at) VALUES (?,?,?,?)",
                edge.packageId(), edge.taskKey(), edge.blockerIncidentKey(),
                Timestamp.from(edge.edgeCreatedAt()));
    }

    /**
     * 追加更正附录（只增不改），返回生成主键。
     */
    public long insertAppendix(IncidentSealAppendix appendix) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_seal_appendices (incident_id, package_id, package_version,"
                            + " record_key, old_value, new_value, reason, actor, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, appendix.incidentId());
            ps.setLong(2, appendix.packageId());
            ps.setInt(3, appendix.packageVersion());
            ps.setString(4, appendix.recordKey());
            ps.setString(5, appendix.oldValue());
            ps.setString(6, appendix.newValue());
            ps.setString(7, appendix.reason());
            ps.setString(8, appendix.actor());
            ps.setTimestamp(9, Timestamp.from(appendix.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件最新（版本最大）的封存包。
     */
    public Optional<IncidentSealPackage> findLatestPackage(long incidentId) {
        List<IncidentSealPackage> rows = jdbc.query(
                "SELECT * FROM incident_seal_packages WHERE incident_id = ?"
                        + " ORDER BY version DESC LIMIT 1",
                PACKAGE_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 按版本查询事件的封存包。
     */
    public Optional<IncidentSealPackage> findPackageByVersion(long incidentId, int version) {
        List<IncidentSealPackage> rows = jdbc.query(
                "SELECT * FROM incident_seal_packages WHERE incident_id = ? AND version = ?",
                PACKAGE_MAPPER, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部封存包，按版本递增返回。
     */
    public List<IncidentSealPackage> listPackages(long incidentId) {
        return jdbc.query("SELECT * FROM incident_seal_packages WHERE incident_id = ?"
                + " ORDER BY version", PACKAGE_MAPPER, incidentId);
    }

    /**
     * 查询包内全部条目，按包内序号（发生时刻、同刻记录标识）返回。
     */
    public List<IncidentSealEntry> listEntries(long packageId) {
        return jdbc.query("SELECT * FROM incident_seal_entries WHERE package_id = ?"
                + " ORDER BY seq_no", ENTRY_MAPPER, packageId);
    }

    /**
     * 按记录标识查询包内条目（附录引用定位用）。
     */
    public List<IncidentSealEntry> findEntriesByRecordKey(long packageId, String recordKey) {
        return jdbc.query("SELECT * FROM incident_seal_entries WHERE package_id = ?"
                + " AND record_key = ? ORDER BY seq_no", ENTRY_MAPPER, packageId, recordKey);
    }

    /**
     * 查询包内全部依赖边快照，按落库顺序返回。
     */
    public List<IncidentSealEdge> listEdges(long packageId) {
        return jdbc.query("SELECT * FROM incident_seal_edges WHERE package_id = ? ORDER BY id",
                EDGE_MAPPER, packageId);
    }

    /**
     * 查询事件全部更正附录，按创建顺序返回。
     */
    public List<IncidentSealAppendix> listAppendices(long incidentId) {
        return jdbc.query("SELECT * FROM incident_seal_appendices WHERE incident_id = ?"
                + " ORDER BY id", APPENDIX_MAPPER, incidentId);
    }

    /**
     * 按主键查询更正附录。
     */
    public Optional<IncidentSealAppendix> findAppendix(long id) {
        List<IncidentSealAppendix> rows = jdbc.query(
                "SELECT * FROM incident_seal_appendices WHERE id = ?", APPENDIX_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 统计某记录标识在事件历史封存包中已收录的次数（TASK 记录版本递增用）。
     */
    public int countSealedEntries(long incidentId, String recordType, String recordKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_seal_entries e"
                        + " JOIN incident_seal_packages p ON p.id = e.package_id"
                        + " WHERE p.incident_id = ? AND e.record_type = ? AND e.record_key = ?",
                Integer.class, incidentId, recordType, recordKey);
        return count == null ? 0 : count;
    }

    /**
     * 查询事件当前全部任务依赖边（任务键、阻塞事件 id 与业务键、边创建时刻），
     * 按任务键、阻塞事件键排序；仅统计任务属于该事件的边（引用任务均属于事件的校验基础）。
     */
    public List<EdgeRow> listIncidentEdges(long incidentId) {
        return jdbc.query("SELECT t.task_key, b.blocker_incident_id, i.incident_key, b.created_at"
                        + " FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id"
                        + " JOIN incidents i ON i.id = b.blocker_incident_id"
                        + " WHERE t.incident_id = ?"
                        + " ORDER BY t.task_key, i.incident_key",
                (rs, n) -> new EdgeRow(rs.getString("task_key"), rs.getLong("blocker_incident_id"),
                        rs.getString("incident_key"), rs.getTimestamp("created_at").toInstant()),
                incidentId);
    }
}
