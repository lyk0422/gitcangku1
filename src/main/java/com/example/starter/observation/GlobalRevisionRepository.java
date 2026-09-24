package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全局版本号与提交顺序锁：global_revision 单行（id=1）。
 *
 * <p>任何生成观测版本的写事务先对该行加行锁（SELECT ... FOR UPDATE）再加一；
 * 不生成版本的解决事务也先取同一把锁，保证解决记录与版本读取的提交顺序一致。
 * 冻结快照事务在读取一致状态前取同一把锁：快照提交前已提交的版本全含，之后的全不含。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class GlobalRevisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public GlobalRevisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 对全局版本单行加行锁并返回当前全局版本号；事务提交（或回滚）后锁释放。
     */
    public long lockAndGet() {
        Long revision = jdbcTemplate.queryForObject(
                "SELECT revision FROM global_revision WHERE id = 1 FOR UPDATE", Long.class);
        if (revision == null) {
            throw new IllegalStateException("global_revision singleton row is missing");
        }
        return revision;
    }

    /**
     * 读取当前全局版本号（不加锁），用于只读按时刻查询。
     */
    public long current() {
        Long revision = jdbcTemplate.queryForObject(
                "SELECT revision FROM global_revision WHERE id = 1", Long.class);
        if (revision == null) {
            throw new IllegalStateException("global_revision singleton row is missing");
        }
        return revision;
    }

    /**
     * 在已持有行锁的事务内将全局版本号加一。
     */
    public void advance() {
        jdbcTemplate.update("UPDATE global_revision SET revision = revision + 1 WHERE id = 1");
    }
}
