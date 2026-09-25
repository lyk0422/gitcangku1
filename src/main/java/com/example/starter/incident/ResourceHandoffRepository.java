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
 * 互助资源、代理人、资源交接及结算的 JDBC 仓储。
 * 写路径均在已锁定相关事件行的事务内执行；资源行在批量校验时按键序 FOR UPDATE 锁定，
 * 交接结束与结算通过锁定交接行串行化，保证交接、任务开始、两侧关闭与租约结算按提交顺序裁决。
 */
@Repository
public class ResourceHandoffRepository {

    private final JdbcTemplate jdbc;

    public ResourceHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IncidentResource> RESOURCE_MAPPER = (rs, n) -> new IncidentResource(
            rs.getLong("id"), rs.getString("resource_key"), rs.getLong("holder_incident_id"),
            rs.getString("acquired_by"), rs.getTimestamp("acquired_at").toInstant());

    private static final RowMapper<IncidentDelegate> DELEGATE_MAPPER = (rs, n) -> new IncidentDelegate(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("delegate"),
            rs.getString("registered_by"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ResourceHandoff> HANDOFF_MAPPER = (rs, n) -> mapHandoff(rs);

    private static final RowMapper<HandoffItem> ITEM_MAPPER = (rs, n) -> {
        Timestamp settledAt = rs.getTimestamp("settled_at");
        return new HandoffItem(rs.getLong("id"), rs.getLong("handoff_id"),
                rs.getString("resource_key"), settledAt == null ? null : settledAt.toInstant());
    };

    private static final RowMapper<HandoffTaskRef> REF_MAPPER = (rs, n) -> new HandoffTaskRef(
            rs.getLong("id"), rs.getLong("handoff_id"), rs.getLong("item_id"),
            rs.getLong("task_id"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<HandoffSettlement> SETTLEMENT_MAPPER = (rs, n) ->
            new HandoffSettlement(rs.getLong("id"), rs.getLong("handoff_id"), rs.getLong("item_id"),
                    rs.getString("resource_key"), SettlementReason.valueOf(rs.getString("reason")),
                    rs.getLong("returned_to_incident_id"),
                    rs.getTimestamp("settled_at").toInstant());

    private static ResourceHandoff mapHandoff(ResultSet rs) throws SQLException {
        Timestamp endTriggeredAt = rs.getTimestamp("end_triggered_at");
        Timestamp settledAt = rs.getTimestamp("settled_at");
        String endReason = rs.getString("end_reason");
        return new ResourceHandoff(rs.getLong("id"), rs.getString("handoff_key"),
                rs.getLong("source_incident_id"), rs.getLong("target_incident_id"),
                rs.getString("receiver"), rs.getLong("source_version"),
                rs.getLong("target_version"), rs.getString("operator"),
                rs.getTimestamp("lease_start").toInstant(), rs.getTimestamp("lease_end").toInstant(),
                HandoffStatus.valueOf(rs.getString("status")),
                endReason == null ? null : SettlementReason.valueOf(endReason),
                endTriggeredAt == null ? null : endTriggeredAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                settledAt == null ? null : settledAt.toInstant());
    }

    // ---------- 资源 ----------

    /**
     * 登记资源，返回生成主键。resource_key 全局唯一约束兜底并发重复登记。
     */
    public long insertResource(IncidentResource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_resources (resource_key, holder_incident_id, acquired_by,"
                            + " acquired_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, resource.resourceKey());
            ps.setLong(2, resource.holderIncidentId());
            ps.setString(3, resource.acquiredBy());
            ps.setTimestamp(4, Timestamp.from(resource.acquiredAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询资源（不加锁），用于只读场景。
     */
    public Optional<IncidentResource> findResource(String resourceKey) {
        List<IncidentResource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE resource_key = ?",
                RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定资源行（SELECT ... FOR UPDATE），用于交接创建串行化。
     */
    public Optional<IncidentResource> lockResource(String resourceKey) {
        List<IncidentResource> rows = jdbc.query(
                "SELECT * FROM incident_resources WHERE resource_key = ? FOR UPDATE",
                RESOURCE_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件登记持有的全部资源，按资源键排序。
     */
    public List<IncidentResource> listResourcesByHolder(long holderIncidentId) {
        return jdbc.query("SELECT * FROM incident_resources WHERE holder_incident_id = ?"
                + " ORDER BY resource_key", RESOURCE_MAPPER, holderIncidentId);
    }

    // ---------- 代理人 ----------

    /**
     * 登记代理人，返回生成主键。(incident_id, delegate) 唯一约束兜底并发重复登记。
     */
    public long insertDelegate(IncidentDelegate delegate) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_delegates (incident_id, delegate, registered_by,"
                            + " created_at) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, delegate.incidentId());
            ps.setString(2, delegate.delegate());
            ps.setString(3, delegate.registeredBy());
            ps.setTimestamp(4, Timestamp.from(delegate.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件是否已登记指定代理人。
     */
    public Optional<IncidentDelegate> findDelegate(long incidentId, String delegate) {
        List<IncidentDelegate> rows = jdbc.query(
                "SELECT * FROM incident_delegates WHERE incident_id = ? AND delegate = ?",
                DELEGATE_MAPPER, incidentId, delegate);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部代理人，按登记顺序返回。
     */
    public List<IncidentDelegate> listDelegates(long incidentId) {
        return jdbc.query("SELECT * FROM incident_delegates WHERE incident_id = ? ORDER BY id",
                DELEGATE_MAPPER, incidentId);
    }

    // ---------- 交接 ----------

    /**
     * 创建交接（ACTIVE），返回生成主键。handoff_key 全局唯一约束兜底并发重复创建。
     */
    public long insertHandoff(ResourceHandoff handoff) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_handoffs (handoff_key, source_incident_id,"
                            + " target_incident_id, receiver, source_version, target_version,"
                            + " operator, lease_start, lease_end, status, end_reason,"
                            + " end_triggered_at, created_at, settled_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, handoff.handoffKey());
            ps.setLong(2, handoff.sourceIncidentId());
            ps.setLong(3, handoff.targetIncidentId());
            ps.setString(4, handoff.receiver());
            ps.setLong(5, handoff.sourceVersion());
            ps.setLong(6, handoff.targetVersion());
            ps.setString(7, handoff.operator());
            ps.setTimestamp(8, Timestamp.from(handoff.leaseStart()));
            ps.setTimestamp(9, Timestamp.from(handoff.leaseEnd()));
            ps.setString(10, handoff.status().name());
            ps.setString(11, handoff.endReason() == null ? null : handoff.endReason().name());
            ps.setTimestamp(12, handoff.endTriggeredAt() == null ? null
                    : Timestamp.from(handoff.endTriggeredAt()));
            ps.setTimestamp(13, Timestamp.from(handoff.createdAt()));
            ps.setTimestamp(14, handoff.settledAt() == null ? null
                    : Timestamp.from(handoff.settledAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询交接（不加锁）。
     */
    public Optional<ResourceHandoff> findHandoffByKey(String handoffKey) {
        List<ResourceHandoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE handoff_key = ?", HANDOFF_MAPPER, handoffKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询交接（不加锁），用于结算与视图组装。
     */
    public Optional<ResourceHandoff> findHandoffById(long handoffId) {
        List<ResourceHandoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE id = ?", HANDOFF_MAPPER, handoffId);
        return rows.stream().findFirst();
    }

    /**
     * 查询目标事件全部 ACTIVE 交接并锁定（SELECT ... FOR UPDATE），用于结束触发与结算串行化。
     */
    public List<ResourceHandoff> lockActiveByTarget(long targetIncidentId) {
        return jdbc.query("SELECT * FROM resource_handoffs WHERE target_incident_id = ?"
                + " AND status = 'ACTIVE' ORDER BY id FOR UPDATE", HANDOFF_MAPPER, targetIncidentId);
    }

    /**
     * 查询事件（作为来源或目标）全部 ACTIVE 交接并锁定（SELECT ... FOR UPDATE），
     * 用于租约到期结算与关闭门禁的串行化。
     */
    public List<ResourceHandoff> lockActiveByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM resource_handoffs WHERE status = 'ACTIVE'"
                        + " AND (source_incident_id = ? OR target_incident_id = ?)"
                        + " ORDER BY id FOR UPDATE",
                HANDOFF_MAPPER, incidentId, incidentId);
    }

    /**
     * 未结算出借项（关闭阻断原因明细）：handoffKey + resourceKey。
     */
    public record UnsettledOutgoing(String handoffKey, String resourceKey) {
    }

    /**
     * 查询来源事件全部未结算的出借资源项（含进行中与已触发结束待结算的交接）。
     */
    public List<UnsettledOutgoing> listUnsettledOutgoing(long sourceIncidentId) {
        return jdbc.query("SELECT h.handoff_key, i.resource_key FROM resource_handoff_items i"
                        + " JOIN resource_handoffs h ON h.id = i.handoff_id"
                        + " WHERE h.source_incident_id = ? AND i.settled_at IS NULL"
                        + " ORDER BY h.id, i.id",
                (rs, n) -> new UnsettledOutgoing(rs.getString("handoff_key"),
                        rs.getString("resource_key")),
                sourceIncidentId);
    }

    /**
     * 查询事件（来源或目标）全部交接，按创建顺序返回。
     */
    public List<ResourceHandoff> listByIncident(long incidentId) {
        return jdbc.query("SELECT * FROM resource_handoffs WHERE source_incident_id = ?"
                + " OR target_incident_id = ? ORDER BY id", HANDOFF_MAPPER, incidentId, incidentId);
    }

    /**
     * 来源事件是否存在未结算的资源项（关闭门禁用）：任一未结算项即禁止来源关闭。
     */
    public boolean hasUnsettledOutgoingItems(long sourceIncidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoff_items i"
                        + " JOIN resource_handoffs h ON h.id = i.handoff_id"
                        + " WHERE h.source_incident_id = ? AND i.settled_at IS NULL",
                Integer.class, sourceIncidentId);
        return count != null && count > 0;
    }

    /**
     * 查询资源当前未结算的交接项（进行中借出），无则表示资源在持有方手中。
     */
    public Optional<HandoffItem> findUnsettledItemByResource(String resourceKey) {
        List<HandoffItem> rows = jdbc.query(
                "SELECT i.* FROM resource_handoff_items i"
                        + " JOIN resource_handoffs h ON h.id = i.handoff_id"
                        + " WHERE i.resource_key = ? AND i.settled_at IS NULL"
                        + " AND h.status = 'ACTIVE'",
                ITEM_MAPPER, resourceKey);
        return rows.stream().findFirst();
    }

    /**
     * 记录交接结束触发（目标关闭或租约到期），仅在未触发过时生效。
     */
    public void markEndTriggered(long handoffId, SettlementReason reason, Instant at) {
        jdbc.update("UPDATE resource_handoffs SET end_reason = ?, end_triggered_at = ?"
                        + " WHERE id = ? AND end_triggered_at IS NULL",
                reason.name(), Timestamp.from(at), handoffId);
    }

    /**
     * 交接全部资源项结算完成后置为 SETTLED 终态。
     */
    public void markHandoffSettled(long handoffId, Instant at) {
        jdbc.update("UPDATE resource_handoffs SET status = 'SETTLED', settled_at = ? WHERE id = ?",
                Timestamp.from(at), handoffId);
    }

    // ---------- 资源项 ----------

    /**
     * 追加交接资源项，返回生成主键。(handoff_id, resource_key) 唯一。
     */
    public long insertItem(HandoffItem item) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_handoff_items (handoff_id, resource_key, settled_at)"
                            + " VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, item.handoffId());
            ps.setString(2, item.resourceKey());
            ps.setTimestamp(3, item.settledAt() == null ? null : Timestamp.from(item.settledAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询交接的全部资源项，按创建顺序返回。
     */
    public List<HandoffItem> listItems(long handoffId) {
        return jdbc.query("SELECT * FROM resource_handoff_items WHERE handoff_id = ? ORDER BY id",
                ITEM_MAPPER, handoffId);
    }

    /**
     * 将资源项标记为已结算（归还来源）。
     */
    public void markItemSettled(long itemId, Instant at) {
        jdbc.update("UPDATE resource_handoff_items SET settled_at = ? WHERE id = ?",
                Timestamp.from(at), itemId);
    }

    // ---------- 任务引用 ----------

    /**
     * 追加资源项-任务引用。(item_id, task_id) 唯一。
     */
    public void insertRef(long handoffId, long itemId, long taskId, Instant now) {
        jdbc.update("INSERT INTO handoff_task_refs (handoff_id, item_id, task_id, created_at)"
                + " VALUES (?,?,?,?)", handoffId, itemId, taskId, Timestamp.from(now));
    }

    /**
     * 查询交接的全部任务引用。
     */
    public List<HandoffTaskRef> listRefsByHandoff(long handoffId) {
        return jdbc.query("SELECT * FROM handoff_task_refs WHERE handoff_id = ? ORDER BY id",
                REF_MAPPER, handoffId);
    }

    /**
     * 查询资源项的剩余任务引用。
     */
    public List<HandoffTaskRef> listRefsByItem(long itemId) {
        return jdbc.query("SELECT * FROM handoff_task_refs WHERE item_id = ? ORDER BY id",
                REF_MAPPER, itemId);
    }

    /**
     * 查询任务当前持有的交接引用（任务终态结算钩子用）。
     */
    public List<HandoffTaskRef> listRefsByTask(long taskId) {
        return jdbc.query("SELECT * FROM handoff_task_refs WHERE task_id = ? ORDER BY id",
                REF_MAPPER, taskId);
    }

    /**
     * 解除交接下所有非 IN_PROGRESS 任务的资源引用（结束触发时未开始任务须解除资源）。
     */
    public void deleteRefsNotInProgress(long handoffId) {
        jdbc.update("DELETE FROM handoff_task_refs WHERE handoff_id = ? AND task_id IN"
                + " (SELECT id FROM incident_tasks WHERE status <> 'IN_PROGRESS')", handoffId);
    }

    /**
     * 解除指定任务的全部交接资源引用（任务终态时调用）。
     */
    public void deleteRefsByTask(long taskId) {
        jdbc.update("DELETE FROM handoff_task_refs WHERE task_id = ?", taskId);
    }

    // ---------- 结算 ----------

    /**
     * 写入不可变交接结算，返回生成主键。uk_settlement_item 唯一约束兜底每项至多一条。
     */
    public long insertSettlement(HandoffSettlement settlement) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO handoff_settlements (handoff_id, item_id, resource_key, reason,"
                            + " returned_to_incident_id, settled_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, settlement.handoffId());
            ps.setLong(2, settlement.itemId());
            ps.setString(3, settlement.resourceKey());
            ps.setString(4, settlement.reason().name());
            ps.setLong(5, settlement.returnedToIncidentId());
            ps.setTimestamp(6, Timestamp.from(settlement.settledAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询交接的全部结算记录，按结算顺序返回。
     */
    public List<HandoffSettlement> listSettlementsByHandoff(long handoffId) {
        return jdbc.query("SELECT * FROM handoff_settlements WHERE handoff_id = ? ORDER BY id",
                SETTLEMENT_MAPPER, handoffId);
    }

    /**
     * 查询事件（作为来源或目标）相关的全部结算记录，按结算顺序返回。
     */
    public List<HandoffSettlement> listSettlementsByIncident(long incidentId) {
        return jdbc.query("SELECT s.* FROM handoff_settlements s"
                        + " JOIN resource_handoffs h ON h.id = s.handoff_id"
                        + " WHERE h.source_incident_id = ? OR h.target_incident_id = ?"
                        + " ORDER BY s.id", SETTLEMENT_MAPPER, incidentId, incidentId);
    }
}
