package com.example.starter.calibration.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 领域版本持久化。domain_state 为固定单行表：
 * 血缘新增、校准提交、审核放行与失效激活均递增版本号；
 * 失效激活在本行行锁内串行化，使并发操作按事务提交顺序生效。
 * 初始单行惰性幂等创建（INSERT ... ON DUPLICATE KEY UPDATE，MySQL 与 H2 MySQL 模式均支持）。
 */
@Repository
public class DomainStateRepository {

    private final JdbcTemplate jdbc;

    private volatile boolean initialized;

    public DomainStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等确保单行版本号存在（首次使用时执行，避免在 Bean 构造期依赖建表脚本顺序）。
     */
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    jdbc.update("INSERT INTO domain_state (id, version) VALUES (1, 0) "
                            + "ON DUPLICATE KEY UPDATE version = version");
                    initialized = true;
                }
            }
        }
    }

    /**
     * 读取当前领域版本号（不加锁）。
     */
    public long currentVersion() {
        ensureInitialized();
        Long version = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 读取当前版本并对单行加行锁（须在事务内调用），用于失效激活的乐观校验与串行化。
     */
    public long currentVersionForUpdate() {
        ensureInitialized();
        Long version = jdbc.queryForObject(
                "SELECT version FROM domain_state WHERE id = 1 FOR UPDATE", Long.class);
        return version == null ? 0L : version;
    }

    /**
     * 递增领域版本号并返回递增后的值。
     */
    public long increment() {
        ensureInitialized();
        jdbc.update("UPDATE domain_state SET version = version + 1 WHERE id = 1");
        return currentVersion();
    }
}
