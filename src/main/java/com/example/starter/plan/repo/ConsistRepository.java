package com.example.starter.plan.repo;

import com.example.starter.plan.model.ConsistCar;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 计划危险品编组车厢持久化。编组整体替换（先删后插），按车厢位置升序读取。
 */
@Repository
public class ConsistRepository {

    private final JdbcTemplate jdbc;

    public ConsistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换计划编组（先删后插）。车厢位置重复的最终拒绝由业务层在事务内提前判定，
     * 唯一约束 uk_rail_plan_consist_plan_position 作为并发兜底。
     */
    public void replaceCars(long planId, List<ConsistCar> cars) {
        jdbc.update("DELETE FROM rail_plan_consist_car WHERE plan_id = ?", planId);
        if (cars.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_consist_car (plan_id, position, hazard_class) VALUES (?, ?, ?)",
                cars, cars.size(),
                (ps, car) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, car.position());
                    if (car.hazardClass() == null) {
                        ps.setNull(3, java.sql.Types.VARCHAR);
                    } else {
                        ps.setString(3, car.hazardClass());
                    }
                });
    }

    /**
     * 查询计划编组，按车厢位置升序；未登记编组时返回空列表。
     */
    public List<ConsistCar> findCars(long planId) {
        return jdbc.query(
                "SELECT id, plan_id, position, hazard_class FROM rail_plan_consist_car"
                        + " WHERE plan_id = ? ORDER BY position",
                (rs, n) -> new ConsistCar(rs.getLong("id"), rs.getLong("plan_id"),
                        rs.getInt("position"), rs.getString("hazard_class")),
                planId);
    }
}
