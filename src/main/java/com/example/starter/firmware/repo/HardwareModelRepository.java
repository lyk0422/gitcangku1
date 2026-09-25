package com.example.starter.firmware.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 已知硬件型号目录数据访问。型号登记后不可变，矩阵型号集合与设备硬件型号都引用此目录。
 */
@Repository
public class HardwareModelRepository {

    private final JdbcTemplate jdbc;

    public HardwareModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true 表示新登记；false 表示型号已存在
     */
    public boolean register(String hardwareModel) {
        try {
            jdbc.update("INSERT INTO hardware_model (model) VALUES (?)", hardwareModel);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean exists(String hardwareModel) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM hardware_model WHERE model = ?",
                Long.class, hardwareModel);
        return count != null && count > 0;
    }

    public List<String> findAll() {
        return jdbc.queryForList("SELECT model FROM hardware_model ORDER BY model", String.class);
    }
}
