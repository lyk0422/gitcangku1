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
 * 残余风险模型、防护屏障与遏制冻结快照的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行（incidents 行 FOR UPDATE）的写事务内，
 * 同一事件的模型变更按事务提交顺序生效。
 */
@Repository
public class RiskModelRepository {

    private final JdbcTemplate jdbc;

    public RiskModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RiskModel> MODEL_MAPPER = (rs, n) -> mapModel(rs);
    private static final RowMapper<RiskBarrier> BARRIER_MAPPER = (rs, n) -> new RiskBarrier(
            rs.getLong("id"), rs.getLong("model_id"), rs.getString("barrier_key"),
            rs.getString("task_key"), rs.getInt("reduce_p"), rs.getInt("reduce_q"),
            rs.getBoolean("revoked"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    private static final RowMapper<ContainmentBarrier> SNAPSHOT_MAPPER = (rs, n) -> new ContainmentBarrier(
            rs.getLong("id"), rs.getLong("model_id"), rs.getString("barrier_key"),
            rs.getString("task_key"), rs.getString("task_status"),
            rs.getInt("reduce_p"), rs.getInt("reduce_q"),
            rs.getTimestamp("created_at").toInstant());

    private static RiskModel mapModel(ResultSet rs) throws SQLException {
        Timestamp containedAt = rs.getTimestamp("contained_at");
        long num = rs.getLong("frozen_residual_num");
        Long frozenNum = rs.wasNull() ? null : num;
        long den = rs.getLong("frozen_residual_den");
        Long frozenDen = rs.wasNull() ? null : den;
        return new RiskModel(rs.getLong("id"), rs.getLong("incident_id"),
                rs.getInt("initial_risk"), rs.getInt("residual_limit"), rs.getLong("version"),
                containedAt == null ? null : containedAt.toInstant(), frozenNum, frozenDen,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 按事件查询风险模型（不加锁）；每事件至多一条。
     */
    public Optional<RiskModel> findByIncident(long incidentId) {
        List<RiskModel> rows = jdbc.query("SELECT * FROM incident_risk_models WHERE incident_id = ?",
                MODEL_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 初始化风险模型（版本置 1），返回生成主键。
     */
    public long insertModel(RiskModel model) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_risk_models (incident_id, initial_risk, residual_limit,"
                            + " version, contained_at, frozen_residual_num, frozen_residual_den,"
                            + " created_at, updated_at) VALUES (?,?,?,?,NULL,NULL,NULL,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, model.incidentId());
            ps.setInt(2, model.initialRisk());
            ps.setInt(3, model.residualLimit());
            ps.setLong(4, model.version());
            ps.setTimestamp(5, Timestamp.from(model.createdAt()));
            ps.setTimestamp(6, Timestamp.from(model.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 推进模型版本（屏障登记/修改/撤销每次成功加一）。
     */
    public void updateVersion(long modelId, long version, Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_models SET version = ?, updated_at = ? WHERE id = ?",
                version, Timestamp.from(updatedAt), modelId);
    }

    /**
     * 遏制冻结：写入遏制 UTC 时刻与冻结残余的精确分数（分子/分母），只写一次。
     */
    public void freeze(long modelId, Instant containedAt, long residualNum, long residualDen,
                       Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_models SET contained_at = ?, frozen_residual_num = ?,"
                        + " frozen_residual_den = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(containedAt), residualNum, residualDen, Timestamp.from(updatedAt),
                modelId);
    }

    /**
     * 登记屏障（未撤销），返回生成主键。(model_id, barrier_key) 唯一约束兜底并发重复。
     */
    public long insertBarrier(RiskBarrier barrier) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_risk_barriers (model_id, barrier_key, task_key, reduce_p,"
                            + " reduce_q, revoked, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, barrier.modelId());
            ps.setString(2, barrier.barrierKey());
            ps.setString(3, barrier.taskKey());
            ps.setInt(4, barrier.reduceP());
            ps.setInt(5, barrier.reduceQ());
            ps.setBoolean(6, barrier.revoked());
            ps.setTimestamp(7, Timestamp.from(barrier.createdAt()));
            ps.setTimestamp(8, Timestamp.from(barrier.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按模型与 barrierKey 查询屏障。
     */
    public Optional<RiskBarrier> findBarrier(long modelId, String barrierKey) {
        List<RiskBarrier> rows = jdbc.query(
                "SELECT * FROM incident_risk_barriers WHERE model_id = ? AND barrier_key = ?",
                BARRIER_MAPPER, modelId, barrierKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询模型全部屏障（含已撤销），按登记顺序返回。
     */
    public List<RiskBarrier> listBarriers(long modelId) {
        return jdbc.query("SELECT * FROM incident_risk_barriers WHERE model_id = ? ORDER BY id",
                BARRIER_MAPPER, modelId);
    }

    /**
     * 统计未撤销屏障数（每事件至多 5 个）。
     */
    public int countActiveBarriers(long modelId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_risk_barriers WHERE model_id = ? AND revoked = FALSE",
                Integer.class, modelId);
        return count == null ? 0 : count;
    }

    /**
     * 修改屏障削减率。
     */
    public void updateBarrierRatio(long barrierId, int reduceP, int reduceQ, Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_barriers SET reduce_p = ?, reduce_q = ?, updated_at = ?"
                        + " WHERE id = ?",
                reduceP, reduceQ, Timestamp.from(updatedAt), barrierId);
    }

    /**
     * 撤销屏障（撤销后不再计入残余削减，也不占用 5 个未撤销屏障名额）。
     */
    public void revokeBarrier(long barrierId, Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_barriers SET revoked = TRUE, updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), barrierId);
    }

    /**
     * 写入遏制冻结屏障快照（遏制时刻的有效屏障：身份、比率、任务状态）。
     */
    public void insertContainmentBarrier(ContainmentBarrier snapshot) {
        jdbc.update("INSERT INTO incident_containment_barriers (model_id, barrier_key, task_key,"
                        + " task_status, reduce_p, reduce_q, created_at) VALUES (?,?,?,?,?,?,?)",
                snapshot.modelId(), snapshot.barrierKey(), snapshot.taskKey(), snapshot.taskStatus(),
                snapshot.reduceP(), snapshot.reduceQ(), Timestamp.from(snapshot.createdAt()));
    }

    /**
     * 查询遏制冻结屏障快照，按登记顺序返回。
     */
    public List<ContainmentBarrier> listContainmentBarriers(long modelId) {
        return jdbc.query("SELECT * FROM incident_containment_barriers WHERE model_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, modelId);
    }
}
