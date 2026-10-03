package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基础数据（除冰车、航班）数据访问，用于存在性校验与并发加锁。
 */
@Repository
public class BaseDataRepository {

    private final JdbcTemplate jdbc;

    public BaseDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean vehicleExists(long vehicleId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM deicing_vehicle WHERE id = ?", Integer.class, vehicleId);
        return count != null && count > 0;
    }

    public boolean flightExists(long flightId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight WHERE id = ?", Integer.class, flightId);
        return count != null && count > 0;
    }

    /**
     * 对除冰车行加排他锁（SELECT ... FOR UPDATE），用于串行化同一车辆的时隙申请。
     */
    public void lockVehicle(long vehicleId) {
        jdbc.queryForObject("SELECT id FROM deicing_vehicle WHERE id = ? FOR UPDATE",
                Long.class, vehicleId);
    }
}