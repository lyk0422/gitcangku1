package com.example.starter.repository;

import com.example.starter.domain.DeicingVehicle;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 除冰车数据访问，提供行级锁用于串行化同一车辆的时隙申请。 */
@Repository
public class VehicleRepository {

    private final JdbcTemplate jdbc;

    public VehicleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeicingVehicle> findById(long id) {
        return jdbc.query("SELECT id, code, name FROM deicing_vehicle WHERE id = ?",
                (rs, n) -> new DeicingVehicle(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                id).stream().findFirst();
    }

    /** 以行锁读取车辆，配合事务串行化同一车辆的并发时隙申请。 */
    public Optional<DeicingVehicle> lockById(long id) {
        return jdbc.query("SELECT id, code, name FROM deicing_vehicle WHERE id = ? FOR UPDATE",
                (rs, n) -> new DeicingVehicle(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                id).stream().findFirst();
    }
}