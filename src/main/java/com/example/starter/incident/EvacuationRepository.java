package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 疏散区域、撤离豁免、任务阻断快照与资源租约的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；批量派工额外持有 resource_lease_lock
 * 单行锁，串行化跨事件的资源可用性校验与租约写入。
 */
@Repository
public class EvacuationRepository {

    /** 资源租约全局锁行的固定主键。 */
    private static final long LEASE_LOCK_ID = 1L;

    private final JdbcTemplate jdbc;

    public EvacuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EvacuationZone> ZONE_MAPPER = (rs, n) -> mapZone(rs);
    private static final RowMapper<ZoneExemption> EXEMPTION_MAPPER = (rs, n) -> new ZoneExemption(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("task_key"),
            rs.getLong("zone_id"), rs.getString("zone_key"), rs.getInt("zone_version"),
            rs.getString("reason"), rs.getString("granted_by"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<TaskZoneBlock> BLOCK_MAPPER = (rs, n) -> mapBlock(rs);
    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> new ResourceLease(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getLong("task_id"),
            rs.getString("resource_key"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null
                    : rs.getTimestamp("released_at").toInstant());

    private static EvacuationZone mapZone(ResultSet rs) throws SQLException {
        Timestamp supersededAt = rs.getTimestamp("superseded_at");
        return new EvacuationZone(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getString("zone_key"), rs.getString("group_key"), rs.getInt("version"),
                Grids.parse(rs.getString("grids")),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_to").toInstant(),
                rs.getString("risk_level"), rs.getString("operator"),
                supersededAt == null ? null : supersededAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    private static TaskZoneBlock mapBlock(ResultSet rs) throws SQLException {
        Timestamp releasedAt = rs.getTimestamp("released_at");
        return new TaskZoneBlock(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getLong("task_id"), rs.getLong("zone_id"), rs.getString("zone_key"),
                rs.getInt("zone_version"), Grids.parse(rs.getString("zone_grids")),
                rs.getTimestamp("zone_effective_from").toInstant(),
                rs.getTimestamp("zone_effective_to").toInstant(),
                rs.getString("risk_level"), rs.getTimestamp("blocked_at").toInstant(),
                releasedAt == null ? null : releasedAt.toInstant());
    }

    // ---------- 疏散区域 ----------

    /**
     * 插入区域（新版本），返回生成主键。zone_key 唯一约束兜底并发重复登记。
     */
    public long insertZone(EvacuationZone zone) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_zones (incident_id, zone_key, group_key, version, grids,"
                            + " effective_from, effective_to, risk_level, operator, superseded_at,"
                            + " created_at) VALUES (?,?,?,?,?,?,?,?,?,NULL,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, zone.incidentId());
            ps.setString(2, zone.zoneKey());
            ps.setString(3, zone.groupKey());
            ps.setInt(4, zone.version());
            ps.setString(5, Grids.canonical(zone.grids()));
            ps.setTimestamp(6, Timestamp.from(zone.effectiveFrom()));
            ps.setTimestamp(7, Timestamp.from(zone.effectiveTo()));
            ps.setString(8, zone.riskLevel());
            ps.setString(9, zone.operator());
            ps.setTimestamp(10, Timestamp.from(zone.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按指纹键查询区域（同键重放用）。
     */
    public Optional<EvacuationZone> findZoneByKey(String zoneKey) {
        List<EvacuationZone> rows = jdbc.query(
                "SELECT * FROM incident_zones WHERE zone_key = ?", ZONE_MAPPER, zoneKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部区域版本，按登记顺序返回。
     */
    public List<EvacuationZone> listZonesByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_zones WHERE incident_id = ? ORDER BY id",
                ZONE_MAPPER, incidentId);
    }

    /**
     * 查询事件当前有效（未被取代）的区域版本，按登记顺序返回。
     */
    public List<EvacuationZone> listCurrentZonesByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_zones WHERE incident_id = ?"
                        + " AND superseded_at IS NULL ORDER BY id", ZONE_MAPPER, incidentId);
    }

    /**
     * 将区域标记为被修订取代。
     */
    public void supersedeZone(long zoneId, Instant at) {
        jdbc.update("UPDATE incident_zones SET superseded_at = ? WHERE id = ?",
                Timestamp.from(at), zoneId);
    }

    // ---------- 撤离豁免 ----------

    /**
     * 签发豁免，返回生成主键。(incident_id, task_key, zone_id, zone_version) 唯一。
     *
     * @throws DuplicateKeyException 同一任务对同一区域版本已持有豁免时抛出
     */
    public long insertExemption(ZoneExemption exemption) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO zone_exemptions (incident_id, task_key, zone_id, zone_key,"
                            + " zone_version, reason, granted_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, exemption.incidentId());
            ps.setString(2, exemption.taskKey());
            ps.setLong(3, exemption.zoneId());
            ps.setString(4, exemption.zoneKey());
            ps.setInt(5, exemption.zoneVersion());
            ps.setString(6, exemption.reason());
            ps.setString(7, exemption.grantedBy());
            ps.setTimestamp(8, Timestamp.from(exemption.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询任务对指定区域版本持有的豁免。
     */
    public Optional<ZoneExemption> findExemption(long incidentId, String taskKey, long zoneId,
                                                 int zoneVersion) {
        List<ZoneExemption> rows = jdbc.query(
                "SELECT * FROM zone_exemptions WHERE incident_id = ? AND task_key = ?"
                        + " AND zone_id = ? AND zone_version = ?",
                EXEMPTION_MAPPER, incidentId, taskKey, zoneId, zoneVersion);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部豁免，按签发顺序返回。
     */
    public List<ZoneExemption> listExemptionsByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM zone_exemptions WHERE incident_id = ? ORDER BY id",
                EXEMPTION_MAPPER, incidentId);
    }

    // ---------- 任务阻断快照 ----------

    /**
     * 固化一条阻断快照。(task_id, zone_id) 唯一，重复阻断由唯一约束兜底（调用前已判重）。
     */
    public void insertBlock(TaskZoneBlock block) {
        jdbc.update("INSERT INTO incident_task_zone_blocks (incident_id, task_id, zone_id,"
                        + " zone_key, zone_version, zone_grids, zone_effective_from,"
                        + " zone_effective_to, risk_level, blocked_at, released_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,NULL)",
                block.incidentId(), block.taskId(), block.zoneId(), block.zoneKey(),
                block.zoneVersion(), Grids.canonical(block.zoneGrids()),
                Timestamp.from(block.zoneEffectiveFrom()),
                Timestamp.from(block.zoneEffectiveTo()), block.riskLevel(),
                Timestamp.from(block.blockedAt()));
    }

    /**
     * 查询任务对指定区域是否存在未解除的阻断。
     */
    public boolean hasActiveBlock(long taskId, long zoneId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_zone_blocks WHERE task_id = ? AND zone_id = ?"
                        + " AND released_at IS NULL", Integer.class, taskId, zoneId);
        return count != null && count > 0;
    }

    /**
     * 查询任务是否存在任何未解除的阻断。
     */
    public boolean hasAnyActiveBlock(long taskId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_zone_blocks WHERE task_id = ?"
                        + " AND released_at IS NULL", Integer.class, taskId);
        return count != null && count > 0;
    }

    /**
     * 解除指定区域的全部未解除阻断（区域结束或被修订取代时）。
     */
    public void releaseBlocksByZone(long zoneId, Instant at) {
        jdbc.update("UPDATE incident_task_zone_blocks SET released_at = ?"
                        + " WHERE zone_id = ? AND released_at IS NULL",
                Timestamp.from(at), zoneId);
    }

    /**
     * 解除指定任务对指定区域的未解除阻断（补发有效豁免时）。
     */
    public void releaseBlock(long taskId, long zoneId, Instant at) {
        jdbc.update("UPDATE incident_task_zone_blocks SET released_at = ?"
                        + " WHERE task_id = ? AND zone_id = ? AND released_at IS NULL",
                Timestamp.from(at), taskId, zoneId);
    }

    /**
     * 查询事件全部阻断快照，按阻断顺序返回。
     */
    public List<TaskZoneBlock> listBlocksByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_task_zone_blocks WHERE incident_id = ?"
                + " ORDER BY id", BLOCK_MAPPER, incidentId);
    }

    // ---------- 资源租约 ----------

    /**
     * 持有资源租约全局锁（单行 SELECT ... FOR UPDATE），串行化资源校验与租约写入。
     * 锁行不存在时先插入；并发首次插入由主键约束串行化。
     */
    public void lockLeases() {
        try {
            jdbc.update("INSERT INTO resource_lease_lock (id) VALUES (?)", LEASE_LOCK_ID);
        } catch (DuplicateKeyException e) {
            // 锁行已存在（含并发事务已提交），继续加锁
        }
        jdbc.queryForObject("SELECT id FROM resource_lease_lock WHERE id = ? FOR UPDATE",
                Long.class, LEASE_LOCK_ID);
    }

    /**
     * 查询指定资源键当前持有中（ACTIVE）的租约。
     */
    public Optional<ResourceLease> findActiveLease(String resourceKey) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM resource_leases WHERE resource_key = ? AND status = 'ACTIVE'",
                LEASE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 获取一条 ACTIVE 租约（批量派工成功时）。
     */
    public void insertLease(long incidentId, long taskId, String resourceKey, Instant at) {
        jdbc.update("INSERT INTO resource_leases (incident_id, task_id, resource_key, status,"
                        + " created_at, released_at) VALUES (?,?,?,'ACTIVE',?,NULL)",
                incidentId, taskId, resourceKey, Timestamp.from(at));
    }

    /**
     * 查询事件全部租约，按获取顺序返回。
     */
    public List<ResourceLease> listLeasesByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM resource_leases WHERE incident_id = ? ORDER BY id",
                LEASE_MAPPER, incidentId);
    }
}
