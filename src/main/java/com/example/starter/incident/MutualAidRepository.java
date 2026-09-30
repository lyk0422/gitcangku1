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
 * 跨事件互助资源的 JDBC 仓储：来源台账、目标租约、分次归还流水。
 * 写路径约定：调用方先在同一事务内按 incidents.id 升序锁定两端事件行，
 * 同租约的并发归还额外锁定租约行；扣减/累加均为带余量条件的条件更新，
 * 由数据库原子裁决，配合非负 CHECK 兜底，保证可用量不为负、归还不超量。
 */
@Repository
public class MutualAidRepository {

    private final JdbcTemplate jdbc;

    public MutualAidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ResourceInventory> INVENTORY_MAPPER = (rs, n) -> new ResourceInventory(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("resource_code"),
            rs.getLong("total_qty"), rs.getLong("available_qty"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ResourceLease> LEASE_MAPPER = (rs, n) -> mapLease(rs);

    private static final RowMapper<ResourceReturn> RETURN_MAPPER = (rs, n) -> new ResourceReturn(
            rs.getLong("id"), rs.getLong("lease_id"), rs.getLong("quantity"),
            rs.getString("returned_by"), rs.getTimestamp("returned_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private static ResourceLease mapLease(ResultSet rs) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new ResourceLease(
                rs.getLong("id"), rs.getString("request_id"),
                rs.getLong("source_incident_id"), rs.getLong("target_incident_id"),
                rs.getString("resource_code"), rs.getLong("quantity"),
                rs.getLong("returned_qty"), LeaseStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 按事件与资源编码查询台账（不加锁）。
     */
    public Optional<ResourceInventory> findInventory(long incidentId, String resourceCode) {
        List<ResourceInventory> rows = jdbc.query(
                "SELECT * FROM incident_resource_inventories WHERE incident_id = ? AND resource_code = ?",
                INVENTORY_MAPPER, incidentId, resourceCode);
        return rows.stream().findFirst();
    }

    /**
     * 按事件与资源编码锁定台账行（SELECT ... FOR UPDATE）。
     */
    public Optional<ResourceInventory> lockInventory(long incidentId, String resourceCode) {
        List<ResourceInventory> rows = jdbc.query(
                "SELECT * FROM incident_resource_inventories WHERE incident_id = ? AND resource_code = ?"
                        + " FOR UPDATE",
                INVENTORY_MAPPER, incidentId, resourceCode);
        return rows.stream().findFirst();
    }

    /**
     * 登记台账：总量与可用量相等，返回生成主键。(incident_id, resource_code) 唯一兜底重复登记。
     */
    public long insertInventory(ResourceInventory inventory) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resource_inventories (incident_id, resource_code, total_qty,"
                            + " available_qty, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, inventory.incidentId());
            ps.setString(2, inventory.resourceCode());
            ps.setLong(3, inventory.totalQty());
            ps.setLong(4, inventory.availableQty());
            ps.setTimestamp(5, Timestamp.from(inventory.createdAt()));
            ps.setTimestamp(6, Timestamp.from(inventory.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 原子扣减可用量：仅当可用量充足时生效。
     *
     * @return 更新行数；0 表示来源不足，调用方必须回滚并返回 422
     */
    public int deductAvailable(long inventoryId, long quantity, Instant now) {
        return jdbc.update("UPDATE incident_resource_inventories SET available_qty = available_qty - ?,"
                        + " updated_at = ? WHERE id = ? AND available_qty >= ?",
                quantity, Timestamp.from(now), inventoryId, quantity);
    }

    /**
     * 原子返还可用量：归还一件恢复一件，不超过登记总量（超出由 CHECK 约束拒绝）。
     */
    public void addAvailable(long inventoryId, long quantity, Instant now) {
        jdbc.update("UPDATE incident_resource_inventories SET available_qty = available_qty + ?,"
                        + " updated_at = ? WHERE id = ?",
                quantity, Timestamp.from(now), inventoryId);
    }

    /**
     * 统计来源事件某资源仍处于 OPEN 租约中的未归还数量合计（件）。
     * 大于 0 表示该资源已被排他租约占用；调用方须已持有来源事件行锁。
     */
    public long sumOpenOutstanding(long sourceIncidentId, String resourceCode) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity - returned_qty), 0) FROM incident_resource_leases"
                        + " WHERE source_incident_id = ? AND resource_code = ? AND status = 'OPEN'",
                Long.class, sourceIncidentId, resourceCode);
        return sum == null ? 0L : sum;
    }

    /**
     * 插入 OPEN 租约，返回生成主键。request_id 全局唯一兜底并发同键。
     */
    public long insertLease(ResourceLease lease) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resource_leases (request_id, source_incident_id,"
                            + " target_incident_id, resource_code, quantity, returned_qty, status,"
                            + " expires_at, closed_at, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lease.requestId());
            ps.setLong(2, lease.sourceIncidentId());
            ps.setLong(3, lease.targetIncidentId());
            ps.setString(4, lease.resourceCode());
            ps.setLong(5, lease.quantity());
            ps.setLong(6, lease.returnedQty());
            ps.setString(7, lease.status().name());
            ps.setTimestamp(8, Timestamp.from(lease.expiresAt()));
            ps.setTimestamp(9, lease.closedAt() == null ? null : Timestamp.from(lease.closedAt()));
            ps.setTimestamp(10, Timestamp.from(lease.createdAt()));
            ps.setTimestamp(11, Timestamp.from(lease.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 requestId 查询租约（不加锁）。
     */
    public Optional<ResourceLease> findLeaseByRequest(String requestId) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM incident_resource_leases WHERE request_id = ?", LEASE_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询租约（不加锁）。
     */
    public Optional<ResourceLease> findLeaseById(long id) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM incident_resource_leases WHERE id = ?", LEASE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键锁定租约行（SELECT ... FOR UPDATE），串行化同租约的并发归还。
     */
    public Optional<ResourceLease> lockLeaseById(long id) {
        List<ResourceLease> rows = jdbc.query(
                "SELECT * FROM incident_resource_leases WHERE id = ? FOR UPDATE", LEASE_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询以某事件为来源或目标的全部租约，按征调落库顺序返回。
     */
    public List<ResourceLease> listLeasesByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM incident_resource_leases WHERE source_incident_id = ?"
                + " OR target_incident_id = ? ORDER BY id", LEASE_MAPPER, incidentId, incidentId);
    }

    /**
     * 原子累加归还数量：仅 OPEN 且本次归还不超过未归还量时生效。
     *
     * @return 更新行数；0 表示租约已关闭或归还超额，调用方回滚并返回 422
     */
    public int addReturnedQty(long leaseId, long quantity, Instant now) {
        return jdbc.update("UPDATE incident_resource_leases SET returned_qty = returned_qty + ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'OPEN'"
                        + " AND returned_qty + ? <= quantity",
                quantity, Timestamp.from(now), leaseId, quantity);
    }

    /**
     * 将恰好结清的租约置为 CLOSED 并记录关闭时刻；仅当前 OPEN 可关闭。
     *
     * @return 更新行数；0 表示已被并发关闭
     */
    public int closeLease(long leaseId, Instant closedAt, Instant now) {
        return jdbc.update("UPDATE incident_resource_leases SET status = 'CLOSED', closed_at = ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(closedAt), Timestamp.from(now), leaseId);
    }

    /**
     * 插入一条分次归还流水，返回生成主键。
     */
    public long insertReturn(ResourceReturn record) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resource_returns (lease_id, quantity, returned_by,"
                            + " returned_at, created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.leaseId());
            ps.setLong(2, record.quantity());
            ps.setString(3, record.returnedBy());
            ps.setTimestamp(4, Timestamp.from(record.returnedAt()));
            ps.setTimestamp(5, Timestamp.from(record.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询租约的全部分次归还流水，按归还顺序返回。
     */
    public List<ResourceReturn> listReturns(long leaseId) {
        return jdbc.query("SELECT * FROM incident_resource_returns WHERE lease_id = ? ORDER BY id",
                RETURN_MAPPER, leaseId);
    }
}
