package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 冻结裁决单行锁：冻结、撤销、发布启动、拉取在同一事务内先锁本行，
 * 使相互之间的生效顺序等于事务提交顺序。
 */
@Repository
public class FreezeGuardRepository {

    private final JdbcTemplate jdbc;

    public FreezeGuardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取冻结裁决行锁（持有至当前事务提交）。
     */
    public void lock() {
        jdbc.query("SELECT id FROM freeze_guard WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> rs.getInt("id"));
    }
}
