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
 * 日志封存包、包条目与更正附录的 JDBC 仓储。
 * 封存写路径先锁定事件行，版本号在同事务内按当前最大版本 +1 确定，
 * (incident_id, version) 唯一约束兜底并发；包与条目一经插入不再 UPDATE/DELETE。
 */
@Repository
public class LogSealRepository {

    private final JdbcTemplate jdbc;

    public LogSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LogSeal> SEAL_MAPPER = (rs, n) -> new LogSeal(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
            rs.getTimestamp("cutoff_at").toInstant(), rs.getTimestamp("sealed_at").toInstant(),
            rs.getString("created_by"), rs.getInt("record_count"), rs.getString("content_hash"));

    private static final RowMapper<LogSealItem> ITEM_MAPPER = (rs, n) -> mapItem(rs);

    private static final RowMapper<LogCorrection> CORRECTION_MAPPER = (rs, n) -> new LogCorrection(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("package_version"),
            rs.getString("record_type"), rs.getString("record_key"), rs.getString("field_name"),
            rs.getString("old_value"), rs.getString("new_value"), rs.getString("reason"),
            rs.getString("operator"), rs.getTimestamp("created_at").toInstant());

    private static LogSealItem mapItem(ResultSet rs) throws SQLException {
        String fromTaskKey = rs.getString("from_task_key");
        String toIncidentKey = rs.getString("to_incident_key");
        return new LogSealItem(rs.getLong("id"), rs.getLong("seal_id"), rs.getInt("seq_no"),
                rs.getString("record_type"), rs.getString("record_key"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getInt("record_version"),
                fromTaskKey, toIncidentKey, rs.getString("payload"));
    }

    /**
     * 插入封存包元数据，返回生成主键。
     */
    public long insertSeal(LogSeal seal) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_seals (incident_id, version, cutoff_at, sealed_at,"
                            + " created_by, record_count, content_hash) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, seal.incidentId());
            ps.setInt(2, seal.version());
            ps.setTimestamp(3, Timestamp.from(seal.cutoffAt()));
            ps.setTimestamp(4, Timestamp.from(seal.sealedAt()));
            ps.setString(5, seal.createdBy());
            ps.setInt(6, seal.recordCount());
            ps.setString(7, seal.contentHash());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 追加一条包条目（按已确定的 seqNo），条目不更新。
     */
    public void insertItem(LogSealItem item) {
        jdbc.update("INSERT INTO incident_log_seal_items (seal_id, seq_no, record_type, record_key,"
                        + " occurred_at, record_version, from_task_key, to_incident_key, payload)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                item.sealId(), item.seqNo(), item.recordType(), item.recordKey(),
                Timestamp.from(item.occurredAt()), item.recordVersion(),
                item.fromTaskKey(), item.toIncidentKey(), item.payload());
    }

    /**
     * 追加一条更正附录，返回生成主键。
     */
    public long insertCorrection(LogCorrection correction) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_log_corrections (incident_id, package_version, record_type,"
                            + " record_key, field_name, old_value, new_value, reason, operator,"
                            + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, correction.incidentId());
            ps.setInt(2, correction.packageVersion());
            ps.setString(3, correction.recordType());
            ps.setString(4, correction.recordKey());
            ps.setString(5, correction.fieldName());
            ps.setString(6, correction.oldValue());
            ps.setString(7, correction.newValue());
            ps.setString(8, correction.reason());
            ps.setString(9, correction.operator());
            ps.setTimestamp(10, Timestamp.from(correction.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件当前最大封存版本；无包时返回 0。
     */
    public int maxVersion(long incidentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM incident_log_seals WHERE incident_id = ?",
                Integer.class, incidentId);
        return max == null ? 0 : max;
    }

    /**
     * 按版本查询封存包元数据。
     */
    public Optional<LogSeal> findSeal(long incidentId, int version) {
        List<LogSeal> rows = jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? AND version = ?",
                SEAL_MAPPER, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部封存包元数据，按版本升序返回。
     */
    public List<LogSeal> listSeals(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_log_seals WHERE incident_id = ? ORDER BY version",
                SEAL_MAPPER, incidentId);
    }

    /**
     * 查询包内全部条目，按 seqNo 升序返回（即发生时刻、同刻标识排序）。
     */
    public List<LogSealItem> listItems(long sealId) {
        return jdbc.query(
                "SELECT * FROM incident_log_seal_items WHERE seal_id = ? ORDER BY seq_no",
                ITEM_MAPPER, sealId);
    }

    /**
     * 按记录类型与记录标识定位包内条目，用于更正附录校验原记录存在。
     */
    public Optional<LogSealItem> findItem(long sealId, String recordType, String recordKey) {
        List<LogSealItem> rows = jdbc.query(
                "SELECT * FROM incident_log_seal_items WHERE seal_id = ? AND record_type = ?"
                        + " AND record_key = ?",
                ITEM_MAPPER, sealId, recordType, recordKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部更正附录，按追加顺序返回。
     */
    public List<LogCorrection> listCorrections(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_log_corrections WHERE incident_id = ? ORDER BY id",
                CORRECTION_MAPPER, incidentId);
    }

    /**
     * 查询指定原包版本的更正附录，按追加顺序返回。
     */
    public List<LogCorrection> listCorrectionsByPackage(long incidentId, int packageVersion) {
        return jdbc.query(
                "SELECT * FROM incident_log_corrections WHERE incident_id = ? AND package_version = ?"
                        + " ORDER BY id",
                CORRECTION_MAPPER, incidentId, packageVersion);
    }
}
