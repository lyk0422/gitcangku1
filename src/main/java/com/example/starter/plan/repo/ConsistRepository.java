package com.example.starter.plan.repo;

import com.example.starter.plan.model.Consist;
import com.example.starter.plan.model.RiskSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 计划编组、车厢集合与站台风险快照的 JDBC 持久化。
 * 编组按计划整体替换；车厢按编号去重、规范化升序后以 seq 存储。
 */
@Repository
public class ConsistRepository {

    private final JdbcTemplate jdbc;

    public ConsistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换计划编组（MERGE 最新行 + 车厢先删后插），须在事务内调用。
     *
     * @param orderedCars 已去重并规范化升序的车厢编号
     */
    public void replaceConsist(long planId, int version, int trainLength, String platformCode,
                               String operator, List<String> orderedCars, long nowMillis) {
        int updated = jdbc.update("UPDATE rail_plan_consist SET version = ?, train_length = ?,"
                        + " platform_code = ?, operator = ?, created_at = ? WHERE plan_id = ?",
                version, trainLength, platformCode, operator, nowMillis, planId);
        if (updated == 0) {
            jdbc.update("INSERT INTO rail_plan_consist (plan_id, version, train_length,"
                            + " platform_code, operator, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    planId, version, trainLength, platformCode, operator, nowMillis);
        }
        jdbc.update("DELETE FROM rail_plan_consist_car WHERE plan_id = ?", planId);
        for (int i = 0; i < orderedCars.size(); i++) {
            jdbc.update("INSERT INTO rail_plan_consist_car (plan_id, car_no, seq) VALUES (?, ?, ?)",
                    planId, orderedCars.get(i), i);
        }
    }

    /**
     * 查询计划编组（含按 seq 升序的车厢集合），未登记返回 empty。
     */
    public Optional<Consist> findConsist(long planId) {
        List<Consist> rows = jdbc.query(
                "SELECT plan_id, version, train_length, platform_code, operator"
                        + " FROM rail_plan_consist WHERE plan_id = ?",
                (rs, n) -> new Consist(rs.getLong("plan_id"), rs.getInt("version"),
                        rs.getInt("train_length"), rs.getString("platform_code"),
                        rs.getString("operator"), findCars(planId)),
                planId);
        return rows.stream().findFirst();
    }

    /**
     * 查询计划的规范化车厢集合（按 seq 升序）。
     */
    public List<String> findCars(long planId) {
        return jdbc.queryForList(
                "SELECT car_no FROM rail_plan_consist_car WHERE plan_id = ? ORDER BY seq",
                String.class, planId);
    }

    /**
     * 固化站台风险快照（仅当计划尚无快照时插入）。
     */
    public void insertRiskSnapshot(long planId, String platformCode, int trainLength,
                                   int platformLengthSnapshot, int planVersion, long nowMillis) {
        jdbc.update("INSERT INTO rail_platform_risk_snapshot (plan_id, platform_code, train_length,"
                        + " platform_length_snapshot, plan_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                planId, platformCode, trainLength, platformLengthSnapshot, planVersion, nowMillis);
    }

    /**
     * 整改通过后删除风险快照。
     */
    public void deleteRiskSnapshot(long planId) {
        jdbc.update("DELETE FROM rail_platform_risk_snapshot WHERE plan_id = ?", planId);
    }

    /**
     * 查询风险快照，不存在返回 empty。
     */
    public Optional<RiskSnapshot> findRiskSnapshot(long planId) {
        return jdbc.query("SELECT plan_id, platform_code, train_length, platform_length_snapshot,"
                        + " plan_version FROM rail_platform_risk_snapshot WHERE plan_id = ?",
                (rs, n) -> new RiskSnapshot(rs.getLong("plan_id"), rs.getString("platform_code"),
                        rs.getInt("train_length"), rs.getInt("platform_length_snapshot"),
                        rs.getInt("plan_version")),
                planId).stream().findFirst();
    }
}
