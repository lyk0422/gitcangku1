package com.example.starter.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 天线仓库：提供天线解析与行级锁，用于按天线串行化并发绑定。
 */
@Repository
public class AntennaRepository {

    private final JdbcTemplate jdbc;

    public AntennaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询天线主键。 */
    public Optional<Long> findIdByCode(String antennaCode) {
        return jdbc.query("SELECT id FROM antenna WHERE antenna_code = ?",
                (rs, rowNum) -> rs.getLong("id"), antennaCode).stream().findFirst();
    }

    /** 登记天线（不存在时插入），返回主键。 */
    public long ensureExists(String antennaCode) {
        Optional<Long> existing = findIdByCode(antennaCode);
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.update("INSERT INTO antenna (antenna_code) VALUES (?)", antennaCode);
        return findIdByCode(antennaCode).orElseThrow();
    }

    /** 对天线行加排他锁（SELECT ... FOR UPDATE），绑定与作废竞争时按提交状态串行裁决。 */
    public void lockById(long antennaId) {
        jdbc.query("SELECT id FROM antenna WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), antennaId);
    }
}