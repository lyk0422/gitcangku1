package com.example.starter.frame;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 规则发布与观测写入的全局串行化锁：coordinate_write_lock 只有固定一行，
 * 写事务内 SELECT ... FOR UPDATE 抢占该行，保证"提交时有效版本"的裁决原子
 * （不会出现按旧规则算 X、按新规则算 Y 的半旧半新结果）。
 */
@Repository
public class CoordinateWriteLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinateWriteLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定全局唯一写锁行；事务提交或回滚后自动释放。锁行由 schema.sql 初始化，缺失属于环境错误。
     */
    public void acquire() {
        try {
            jdbcTemplate.queryForObject(
                    "SELECT lock_key FROM coordinate_write_lock WHERE lock_key = 1 FOR UPDATE",
                    Integer.class);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("coordinate write lock row is not initialized", e);
        }
    }
}
