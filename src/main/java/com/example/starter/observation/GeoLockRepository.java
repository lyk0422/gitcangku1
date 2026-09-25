package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 坐标写操作全局互斥锁：基准修改、观测提交与人工裁决在事务内锁定唯一行，
 * 由数据库行锁按提交顺序串行裁决并发写。
 */
@Repository
public class GeoLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public GeoLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在写事务内获取全局行锁（SELECT ... FOR UPDATE）；并发写操作在此按到达顺序串行化。
     */
    public void lockGlobal() {
        jdbcTemplate.query("SELECT lock_name FROM geo_lock WHERE lock_name = 'GLOBAL' FOR UPDATE",
                (rs, rowNum) -> rs.getString("lock_name"));
    }
}
