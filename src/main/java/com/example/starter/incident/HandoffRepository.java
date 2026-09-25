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
 * 互助资源交接与不可变结算的 JDBC 仓储。
 * handoff_key 全局唯一支撑幂等；同资源 ACTIVE 交接租约重叠由查询校验，
 * 资源行 FOR UPDATE 锁串行化并发交接；结算只追加，一条交接恰好一条结算。
 */
@Repository
public class HandoffRepository {

    private final JdbcTemplate jdbc;

    public HandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Handoff> HANDOFF_MAPPER = (rs, n) -> mapHandoff(rs);
    private static final RowMapper<HandoffSettlement> SETTLEMENT_MAPPER = (rs, n) ->
            new HandoffSettlement(rs.getLong("id"), rs.getLong("handoff_id"),
                    rs.getString("reason"), rs.getString("returned_resource_key"),
                    rs.getString("detail"), rs.getTimestamp("settled_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());

    private static Handoff mapHandoff(ResultSet rs) throws SQLException {
        Timestamp settledAt = rs.getTimestamp("settled_at");
        return new Handoff(
                rs.getLong("id"), rs.getString("handoff_key"), rs.getLong("resource_id"),
                rs.getLong("source_incident_id"), rs.getLong("target_incident_id"),
                rs.getLong("source_version"), rs.getLong("target_version"),
                rs.getTimestamp("lease_start").toInstant(), rs.getTimestamp("lease_end").toInstant(),
                rs.getString("operator"), rs.getString("receiver"),
                HandoffStatus.valueOf(rs.getString("status")),
                settledAt == null ? null : settledAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入 ACTIVE 交接，返回生成主键。
     */
    public long insert(Handoff handoff) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO resource_handoffs (handoff_key, resource_id, source_incident_id,"
                            + " target_incident_id, source_version, target_version, lease_start,"
                            + " lease_end, operator, receiver, status, settled_at, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, handoff.handoffKey());
            ps.setLong(2, handoff.resourceId());
            ps.setLong(3, handoff.sourceIncidentId());
            ps.setLong(4, handoff.targetIncidentId());
            ps.setLong(5, handoff.sourceVersion());
            ps.setLong(6, handoff.targetVersion());
            ps.setTimestamp(7, Timestamp.from(handoff.leaseStart()));
            ps.setTimestamp(8, Timestamp.from(handoff.leaseEnd()));
            ps.setString(9, handoff.operator());
            ps.setString(10, handoff.receiver());
            ps.setString(11, handoff.status().name());
            ps.setTimestamp(12, handoff.settledAt() == null ? null : Timestamp.from(handoff.settledAt()));
            ps.setTimestamp(13, Timestamp.from(handoff.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询交接（不加锁）。
     */
    public Optional<Handoff> findByKey(String handoffKey) {
        List<Handoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE handoff_key = ?", HANDOFF_MAPPER, handoffKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定交接行（SELECT ... FOR UPDATE）。
     */
    public Optional<Handoff> lockByKey(String handoffKey) {
        List<Handoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE handoff_key = ? FOR UPDATE",
                HANDOFF_MAPPER, handoffKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询交接（不加锁）。
     */
    public Optional<Handoff> findById(long id) {
        List<Handoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE id = ?", HANDOFF_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询并锁定交接行（SELECT ... FOR UPDATE）。
     */
    public Optional<Handoff> lockById(long id) {
        List<Handoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE id = ? FOR UPDATE", HANDOFF_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源当前 ACTIVE 交接（同一资源至多一条）。
     */
    public Optional<Handoff> findActiveByResource(long resourceId) {
        List<Handoff> rows = jdbc.query(
                "SELECT * FROM resource_handoffs WHERE resource_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id", HANDOFF_MAPPER, resourceId);
        return rows.stream().findFirst();
    }

    /**
     * 判断资源是否存在与 [start,end) 重叠的 ACTIVE 交接租约。
     * UTC 左闭右开：重叠条件为既有租约 start &lt; 请求 end 且 请求 start &lt; 既有 end。
     */
    public boolean existsActiveOverlap(long resourceId, Instant start, Instant end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE resource_id = ? AND status = 'ACTIVE'"
                        + " AND lease_start < ? AND ? < lease_end",
                Integer.class, resourceId, Timestamp.from(end), Timestamp.from(start));
        return count != null && count > 0;
    }

    /**
     * 查询来源事件当前全部 ACTIVE 交接（来源关闭阻断用），按创建顺序返回。
     */
    public List<Handoff> listActiveBySource(long sourceIncidentId) {
        return jdbc.query(
                "SELECT * FROM resource_handoffs WHERE source_incident_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id", HANDOFF_MAPPER, sourceIncidentId);
    }

    /**
     * 查询目标事件当前全部 ACTIVE 交接（目标关闭/结算时使用），按创建顺序返回。
     */
    public List<Handoff> listActiveByTarget(long targetIncidentId) {
        return jdbc.query(
                "SELECT * FROM resource_handoffs WHERE target_incident_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id", HANDOFF_MAPPER, targetIncidentId);
    }

    /**
     * 查询资源全部交接（含已结算），按创建顺序返回。
     */
    public List<Handoff> listByResource(long resourceId) {
        return jdbc.query(
                "SELECT * FROM resource_handoffs WHERE resource_id = ? ORDER BY id",
                HANDOFF_MAPPER, resourceId);
    }

    /**
     * 将交接置为 SETTLED 并记录结算时刻；仅 ACTIVE 可结算，返回受影响行数。
     */
    public int markSettled(long id, Instant settledAt) {
        return jdbc.update(
                "UPDATE resource_handoffs SET status = 'SETTLED', settled_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(settledAt), id);
    }

    /**
     * 追加不可变结算记录（handoff_id 唯一约束兜底重复结算）。
     */
    public long insertSettlement(HandoffSettlement settlement) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO handoff_settlements (handoff_id, reason, returned_resource_key,"
                            + " detail, settled_at, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, settlement.handoffId());
            ps.setString(2, settlement.reason());
            ps.setString(3, settlement.returnedResourceKey());
            ps.setString(4, settlement.detail());
            ps.setTimestamp(5, Timestamp.from(settlement.settledAt()));
            ps.setTimestamp(6, Timestamp.from(settlement.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询交接的结算记录。
     */
    public Optional<HandoffSettlement> findSettlement(long handoffId) {
        List<HandoffSettlement> rows = jdbc.query(
                "SELECT * FROM handoff_settlements WHERE handoff_id = ?", SETTLEMENT_MAPPER, handoffId);
        return rows.stream().findFirst();
    }

    /**
     * 查询资源的全部结算记录（经交接关联），按结算 id 顺序返回。
     */
    public List<HandoffSettlement> listSettlementsByResource(long resourceId) {
        return jdbc.query(
                "SELECT s.* FROM handoff_settlements s JOIN resource_handoffs h"
                        + " ON h.id = s.handoff_id WHERE h.resource_id = ? ORDER BY s.id",
                SETTLEMENT_MAPPER, resourceId);
    }
}
