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
 * 跨事件互助资源征调的 JDBC 仓储：来源事件资源可用量、目标租约与分次归还流水。
 * 写路径均处于先锁定相关事件行（必要时再锁租约行）的事务内；
 * 扣减采用带 available_quantity &gt;= ? 条件的原子 UPDATE，CHECK 约束再次兜底，
 * 保证并发征调同一资源时来源可用量不会为负。
 */
@Repository
public class ResourceAidRepository {

    private final JdbcTemplate jdbc;

    public ResourceAidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceInventory> INVENTORY_MAPPER = (rs, n) -> new ResourceInventory(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("resource_code"),
            rs.getInt("available_quantity"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<AidLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static final RowMapper<AidReturn> RETURN_MAPPER = (rs, n) -> new AidReturn(
            rs.getLong("id"), rs.getLong("lease_id"), rs.getInt("quantity"),
            rs.getString("returned_by"), rs.getTimestamp("returned_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static AidLease mapLease(ResultSet rs) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new AidLease(
                rs.getLong("id"), rs.getString("request_id"),
                rs.getLong("source_incident_id"), rs.getLong("target_incident_id"),
                rs.getString("resource_code"), rs.getInt("quantity"),
                rs.getInt("returned_quantity"), rs.getInt("exclusive") != 0,
                AidLeaseStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant());
    }

    /**
     * 登记事件资源可用量（仅首次），返回生成主键；(incident_id, resource_code) 重复时抛唯一约束异常。
     */
    public long insertInventory(ResourceInventory inventory) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resource_inventories (incident_id, resource_code,"
                            + " available_quantity, created_at, updated_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, inventory.incidentId());
            ps.setString(2, inventory.resourceCode());
            ps.setInt(3, inventory.availableQuantity());
            ps.setTimestamp(4, Timestamp.from(inventory.createdAt()));
            ps.setTimestamp(5, Timestamp.from(inventory.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与资源编码查询库存（不加锁）。
     */
    public Optional<ResourceInventory> findInventory(long incidentId, String resourceCode) {
        List<ResourceInventory> rows = jdbc.query(
                "SELECT * FROM incident_resource_inventories WHERE incident_id = ? AND resource_code = ?",
                INVENTORY_MAPPER, incidentId, resourceCode);
        return rows.stream().findFirst();
    }

    /**
     * 按事件与资源编码锁定库存行（SELECT ... FOR UPDATE）。
     */
    public Optional<ResourceInventory> lockInventory(long incidentId, String resourceCode) {
        List<ResourceInventory> rows = jdbc.query(
                "SELECT * FROM incident_resource_inventories WHERE incident_id = ? AND resource_code = ?"
                        + " FOR UPDATE",
                INVENTORY_MAPPER, incidentId, resourceCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件登记的全部资源库存，按资源编码排序。
     */
    public List<ResourceInventory> listInventories(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_resource_inventories WHERE incident_id = ?"
                        + " ORDER BY resource_code",
                INVENTORY_MAPPER, incidentId);
    }

    /**
     * 原子扣减来源可用量：仅当库存存在且可用量充足时更新，返回受影响行数（0 表示库存不存在或不足）。
     * 与行锁、CHECK(available_quantity &gt;= 0) 共同保证可用量不为负。
     */
    public int decreaseAvailable(long incidentId, String resourceCode, int quantity, Instant at) {
        return jdbc.update("UPDATE incident_resource_inventories SET available_quantity ="
                        + " available_quantity - ?, updated_at = ? WHERE incident_id = ?"
                        + " AND resource_code = ? AND available_quantity >= ?",
                quantity, Timestamp.from(at), incidentId, resourceCode, quantity);
    }

    /**
     * 按实际归还数量加回来源可用量（每次归还调用一次，租约关闭不再额外释放，避免重复释放）。
     */
    public void increaseAvailable(long incidentId, String resourceCode, int quantity, Instant at) {
        int updated = jdbc.update("UPDATE incident_resource_inventories SET available_quantity ="
                        + " available_quantity + ?, updated_at = ? WHERE incident_id = ?"
                        + " AND resource_code = ?",
                quantity, Timestamp.from(at), incidentId, resourceCode);
        if (updated == 0) {
            throw new IllegalStateException("归还时来源库存缺失: incidentId=" + incidentId
                    + ", resource=" + resourceCode);
        }
    }

    /**
     * 插入征调租约（ACTIVE），返回生成主键；request_id 全局唯一，并发同键由唯一约束串行化。
     */
    public long insertLease(AidLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_aid_leases (request_id, source_incident_id,"
                            + " target_incident_id, resource_code, quantity, returned_quantity,"
                            + " exclusive, status, expires_at, created_by, created_at, closed_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lease.requestId());
            ps.setLong(2, lease.sourceIncidentId());
            ps.setLong(3, lease.targetIncidentId());
            ps.setString(4, lease.resourceCode());
            ps.setInt(5, lease.quantity());
            ps.setInt(6, lease.returnedQuantity());
            ps.setInt(7, lease.exclusive() ? 1 : 0);
            ps.setString(8, lease.status().name());
            ps.setTimestamp(9, Timestamp.from(lease.expiresAt()));
            ps.setString(10, lease.createdBy());
            ps.setTimestamp(11, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(12, lease.closedAt() == null ? null : Timestamp.from(lease.closedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按征调 requestId 查询租约（不加锁），用于只读与幂等重放预判。
     */
    public Optional<AidLease> findLeaseByRequest(String requestId) {
        List<AidLease> rows = jdbc.query(
                "SELECT * FROM resource_aid_leases WHERE request_id = ?", LEASE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按征调 requestId 锁定租约行（SELECT ... FOR UPDATE），用于归还结算与并发同键重查。
     */
    public Optional<AidLease> lockLeaseByRequest(String requestId) {
        List<AidLease> rows = jdbc.query(
                "SELECT * FROM resource_aid_leases WHERE request_id = ? FOR UPDATE",
                LEASE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 查询来源事件某资源上全部仍 ACTIVE 的租约（含共享与排他），按创建顺序返回。
     * 用于：存在排他租约时拒绝新征调；排他征调时拒绝任何未结出租约。调用前应已锁定来源事件行。
     */
    public List<AidLease> findActiveLeases(long sourceIncidentId, String resourceCode) {
        return jdbc.query(
                "SELECT * FROM resource_aid_leases WHERE source_incident_id = ? AND resource_code = ?"
                        + " AND status = 'ACTIVE' ORDER BY id",
                LEASE_MAPPER, sourceIncidentId, resourceCode);
    }

    /**
     * 查询与事件关联的全部租约（作为来源或目标），历史（含 CLOSED）一并返回，按创建顺序。
     */
    public List<AidLease> listLeasesByIncident(long incidentId) {
        return jdbc.query(
                "SELECT * FROM resource_aid_leases WHERE source_incident_id = ?"
                        + " OR target_incident_id = ? ORDER BY id",
                LEASE_MAPPER, incidentId, incidentId);
    }

    /**
     * 追加一条分次归还流水，返回生成主键。
     */
    public long insertReturn(AidReturn aidReturn) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_aid_returns (lease_id, quantity, returned_by, returned_at,"
                            + " created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, aidReturn.leaseId());
            ps.setInt(2, aidReturn.quantity());
            ps.setString(3, aidReturn.returnedBy());
            ps.setTimestamp(4, Timestamp.from(aidReturn.returnedAt()));
            ps.setTimestamp(5, Timestamp.from(aidReturn.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 累计已还数量：仅当本次归还不超过未归还量时更新，返回受影响行数（0 表示并发下已超量）。
     */
    public int accumulateReturned(long leaseId, int quantity) {
        return jdbc.update("UPDATE resource_aid_leases SET returned_quantity = returned_quantity + ?"
                        + " WHERE id = ? AND returned_quantity + ? <= quantity",
                quantity, leaseId, quantity);
    }

    /**
     * 恰好还完时将 ACTIVE 租约关闭；仅当确实结清时生效，返回受影响行数。
     * 关闭只改状态，不再释放来源额度（实际数量已在每次归还时加回），杜绝重复释放。
     */
    public int closeIfSettled(long leaseId, Instant closedAt) {
        return jdbc.update("UPDATE resource_aid_leases SET status = 'CLOSED', closed_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE' AND returned_quantity = quantity",
                Timestamp.from(closedAt), leaseId);
    }

    /**
     * 查询租约全部分次归还流水，按归还顺序返回。
     */
    public List<AidReturn> listReturns(long leaseId) {
        return jdbc.query("SELECT * FROM resource_aid_returns WHERE lease_id = ? ORDER BY id",
                RETURN_MAPPER, leaseId);
    }
}
