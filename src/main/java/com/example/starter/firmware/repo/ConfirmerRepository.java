package com.example.starter.firmware.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 紧急例外确认人登记数据访问。
 */
@Repository
public class ConfirmerRepository {

    private final JdbcTemplate jdbc;

    public ConfirmerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true 登记成功；false 表示同名确认人已存在
     */
    public boolean insert(String name) {
        try {
            jdbc.update("INSERT INTO freeze_confirmer (name) VALUES (?)", name);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean exists(String name) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM freeze_confirmer WHERE name = ?",
                Long.class, name);
        return count != null && count > 0;
    }

    public List<String> findAll() {
        return jdbc.queryForList("SELECT name FROM freeze_confirmer ORDER BY name", String.class);
    }
}
