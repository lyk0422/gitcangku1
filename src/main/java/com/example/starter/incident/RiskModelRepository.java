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
 * 合成残余风险模型、防护屏障及遏制冻结快照的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行（incidents 行 FOR UPDATE）的写事务内，
 * 模型版本检查与屏障变更在同一事务串行生效，并发写按事务提交顺序生效。
 */
@Repository
public class RiskModelRepository {

    private final JdbcTemplate jdbc;

    public RiskModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RiskModel> MODEL_MAPPER = (rs, n) -> new RiskModel(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("initial_risk"),
            rs.getInt("residual_limit"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<RiskBarrier> BARRIER_MAPPER = (rs, n) -> new RiskBarrier(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("barrier_key"),
            rs.getString("task_key"), rs.getInt("numerator"), rs.getInt("denominator"),
            rs.getInt("revoked") != 0,
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<ContainmentSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new ContainmentSnapshot(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getString("barrier_key"),
            rs.getString("task_key"), rs.getString("task_status"),
            rs.getInt("numerator"), rs.getInt("denominator"),
            rs.getTimestamp("frozen_at").toInstant());

    /**
     * 按事件查询风险模型（不加锁），用于只读场景与写事务内读取。
     */
    public Optional<RiskModel> findModel(long incidentId) {
        List<RiskModel> rows = jdbc.query("SELECT * FROM incident_risk_models WHERE incident_id = ?",
                MODEL_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 首次插入风险模型（initialRisk/residualLimit 此后不可修改，版本从 0 开始），返回生成主键。
     */
    public long insertModel(RiskModel model) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_risk_models (incident_id, initial_risk, residual_limit, version,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
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
     * 模型版本加一（屏障修改/撤销成功后调用，与业务写入同事务提交）。
     */
    public void bumpVersion(long modelId, Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_models SET version = version + 1, updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), modelId);
    }

    /**
     * 登记屏障（未撤销），返回生成主键。(incident_id, barrier_key) 唯一约束兜底并发重复。
     */
    public long insertBarrier(RiskBarrier barrier) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_risk_barriers (incident_id, barrier_key, task_key, numerator,"
                            + " denominator, revoked, created_at, updated_at) VALUES (?,?,?,?,?,0,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, barrier.incidentId());
            ps.setString(2, barrier.barrierKey());
            ps.setString(3, barrier.taskKey());
            ps.setInt(4, barrier.numerator());
            ps.setInt(5, barrier.denominator());
            ps.setTimestamp(6, Timestamp.from(barrier.createdAt()));
            ps.setTimestamp(7, Timestamp.from(barrier.updatedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按事件与 barrierKey 查询屏障，用于存在性与内容校验。
     */
    public Optional<RiskBarrier> findBarrier(long incidentId, String barrierKey) {
        List<RiskBarrier> rows = jdbc.query(
                "SELECT * FROM incident_risk_barriers WHERE incident_id = ? AND barrier_key = ?",
                BARRIER_MAPPER, incidentId, barrierKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部屏障（含已撤销），按登记顺序返回。
     */
    public List<RiskBarrier> listBarriers(long incidentId) {
        return jdbc.query("SELECT * FROM incident_risk_barriers WHERE incident_id = ? ORDER BY id",
                BARRIER_MAPPER, incidentId);
    }

    /**
     * 统计事件未撤销屏障数（每事件至多 5 个）。
     */
    public int countActiveBarriers(long incidentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_risk_barriers WHERE incident_id = ? AND revoked = 0",
                Integer.class, incidentId);
        return count == null ? 0 : count;
    }

    /**
     * 修改屏障的任务绑定与削减率（不触碰撤销标记）。
     */
    public void updateBarrier(long barrierId, String taskKey, int numerator, int denominator,
                              Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_barriers SET task_key = ?, numerator = ?, denominator = ?,"
                        + " updated_at = ? WHERE id = ?",
                taskKey, numerator, denominator, Timestamp.from(updatedAt), barrierId);
    }

    /**
     * 撤销屏障（置 revoked=1，不可再修改，不计入残余）。
     */
    public void revokeBarrier(long barrierId, Instant updatedAt) {
        jdbc.update("UPDATE incident_risk_barriers SET revoked = 1, updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), barrierId);
    }

    /**
     * 遏制时刻写入一条有效屏障冻结快照（同一事件同一冻结时刻写一批，只写一次）。
     */
    public void insertSnapshot(ContainmentSnapshot snapshot) {
        jdbc.update("INSERT INTO incident_containment_snapshots (incident_id, barrier_key, task_key,"
                        + " task_status, numerator, denominator, frozen_at) VALUES (?,?,?,?,?,?,?)",
                snapshot.incidentId(), snapshot.barrierKey(), snapshot.taskKey(), snapshot.taskStatus(),
                snapshot.numerator(), snapshot.denominator(), Timestamp.from(snapshot.frozenAt()));
    }

    /**
     * 查询事件的遏制冻结快照，按写入顺序返回；未遏制过则为空。
     */
    public List<ContainmentSnapshot> listSnapshots(long incidentId) {
        return jdbc.query("SELECT * FROM incident_containment_snapshots WHERE incident_id = ?"
                + " ORDER BY id", SNAPSHOT_MAPPER, incidentId);
    }
}
