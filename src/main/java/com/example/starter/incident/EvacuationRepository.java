package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 疏散区域与撤离豁免的 JDBC 仓储。
 * 区域网格以规范化后的 JSON 数组存储；所有写路径处于先锁定事件行的写事务内，
 * 区域登记与豁免授予的并发由 (incident_id, fingerprint)/(zone_id, task_key) 唯一约束兜底。
 */
@Repository
public class EvacuationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvacuationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private static final TypeReference<List<String>> GRID_LIST = new TypeReference<>() {
    };

    private final RowMapper<EvacuationZone> zoneMapper = (rs, n) -> mapZone(rs);

    private static final RowMapper<EvacuationExemption> EXEMPTION_MAPPER = (rs, n) ->
            new EvacuationExemption(rs.getLong("id"), rs.getLong("incident_id"),
                    rs.getLong("zone_id"), rs.getInt("version"), rs.getString("exempt_task_key"),
                    rs.getString("granted_by"), rs.getString("command_key"),
                    rs.getTimestamp("created_at").toInstant());

    private EvacuationZone mapZone(ResultSet rs) throws SQLException {
        Timestamp endedAt = rs.getTimestamp("ended_at");
        return new EvacuationZone(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("zone_key"),
                rs.getInt("version"), RiskLevel.valueOf(rs.getString("risk_level")),
                readGrids(rs.getString("grids")),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_to").toInstant(),
                ZoneStatus.valueOf(rs.getString("status")),
                rs.getString("registered_by"), rs.getString("fingerprint"),
                endedAt == null ? null : endedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private List<String> readGrids(String json) {
        try {
            return objectMapper.readValue(json, GRID_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("疏散区域网格反序列化失败", e);
        }
    }

    private String writeGrids(List<String> grids) {
        try {
            return objectMapper.writeValueAsString(grids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("疏散区域网格序列化失败", e);
        }
    }

    /**
     * 插入疏散区域，返回生成主键。
     *
     * @throws DuplicateKeyException zoneKey 或 fingerprint 冲突时抛出
     */
    public long insertZone(EvacuationZone zone) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO evacuation_zones (incident_id, zone_key, version, risk_level, grids,"
                            + " grid_count, effective_from, effective_to, status, registered_by,"
                            + " fingerprint, ended_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, zone.incidentId());
            ps.setString(2, zone.zoneKey());
            ps.setInt(3, zone.version());
            ps.setString(4, zone.riskLevel().name());
            ps.setString(5, writeGrids(zone.grids()));
            ps.setInt(6, zone.grids().size());
            ps.setTimestamp(7, Timestamp.from(zone.effectiveFrom()));
            ps.setTimestamp(8, Timestamp.from(zone.effectiveTo()));
            ps.setString(9, zone.status().name());
            ps.setString(10, zone.registeredBy());
            ps.setString(11, zone.fingerprint());
            ps.setTimestamp(12, zone.endedAt() == null ? null : Timestamp.from(zone.endedAt()));
            ps.setTimestamp(13, Timestamp.from(zone.createdAt()));
            ps.setTimestamp(14, Timestamp.from(zone.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 zoneKey 查询区域（不加锁）。
     */
    public Optional<EvacuationZone> findZoneByKey(long incidentId, String zoneKey) {
        List<EvacuationZone> rows = jdbc.query(
                "SELECT * FROM evacuation_zones WHERE incident_id = ? AND zone_key = ?",
                zoneMapper, incidentId, zoneKey);
        return rows.stream().findFirst();
    }

    /**
     * 按事件与指纹查询区域（同指纹重放用）。
     */
    public Optional<EvacuationZone> findZoneByFingerprint(long incidentId, String fingerprint) {
        List<EvacuationZone> rows = jdbc.query(
                "SELECT * FROM evacuation_zones WHERE incident_id = ? AND fingerprint = ?",
                zoneMapper, incidentId, fingerprint);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部疏散区域，按版本（登记顺序）返回。
     */
    public List<EvacuationZone> listZones(long incidentId) {
        return jdbc.query(
                "SELECT * FROM evacuation_zones WHERE incident_id = ? ORDER BY version",
                zoneMapper, incidentId);
    }

    /**
     * 查询事件当前 REGISTERED 的全部区域（裁决/结束扫描用），按版本返回。
     */
    public List<EvacuationZone> listActiveZones(long incidentId) {
        return jdbc.query(
                "SELECT * FROM evacuation_zones WHERE incident_id = ? AND status = 'REGISTERED'"
                        + " ORDER BY version", zoneMapper, incidentId);
    }

    /**
     * 按主键查询区域。
     */
    public Optional<EvacuationZone> findZoneById(long zoneId) {
        List<EvacuationZone> rows = jdbc.query(
                "SELECT * FROM evacuation_zones WHERE id = ?", zoneMapper, zoneId);
        return rows.stream().findFirst();
    }

    /**
     * 将区域置为 ENDED 并记录结束时刻（条件更新，返回受影响行数）。
     */
    public int markEnded(long zoneId, Instant endedAt) {
        return jdbc.update("UPDATE evacuation_zones SET status = 'ENDED', ended_at = ?,"
                + " updated_at = ? WHERE id = ? AND status = 'REGISTERED'",
                Timestamp.from(endedAt), Timestamp.from(endedAt), zoneId);
    }

    /**
     * 事件内下一区域版本号（当前最大版本 + 1，无区域时为 1）。
     */
    public int nextVersion(long incidentId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0) FROM evacuation_zones WHERE incident_id = ?",
                Integer.class, incidentId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 授予撤离豁免，返回生成主键。(zone_id, exempt_task_key) 唯一兜底重复授予。
     */
    public long insertExemption(EvacuationExemption exemption) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO evacuation_exemptions (incident_id, zone_id, version,"
                            + " exempt_task_key, granted_by, command_key, created_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, exemption.incidentId());
            ps.setLong(2, exemption.zoneId());
            ps.setInt(3, exemption.version());
            ps.setString(4, exemption.exemptTaskKey());
            ps.setString(5, exemption.grantedBy());
            ps.setString(6, exemption.commandKey());
            ps.setTimestamp(7, Timestamp.from(exemption.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询某任务在指定区域上的豁免（不校验版本，版本由服务层比对）。
     */
    public Optional<EvacuationExemption> findExemption(long zoneId, String taskKey) {
        List<EvacuationExemption> rows = jdbc.query(
                "SELECT * FROM evacuation_exemptions WHERE zone_id = ? AND exempt_task_key = ?",
                EXEMPTION_MAPPER, zoneId, taskKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部豁免（豁免版本查询用），按授予顺序返回。
     */
    public List<EvacuationExemption> listExemptions(long incidentId) {
        return jdbc.query(
                "SELECT * FROM evacuation_exemptions WHERE incident_id = ? ORDER BY id",
                EXEMPTION_MAPPER, incidentId);
    }

    /**
     * 查询某任务在事件内的全部豁免（按授予顺序）。
     */
    public List<EvacuationExemption> listExemptionsForTask(long incidentId, String taskKey) {
        return jdbc.query(
                "SELECT * FROM evacuation_exemptions WHERE incident_id = ? AND exempt_task_key = ?"
                        + " ORDER BY id", EXEMPTION_MAPPER, incidentId, taskKey);
    }
}
