package com.example.starter.consent;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试数据库夹具：每个测试前清空全部业务表并重新播种 catalogGeneration=1 的初始目录，
 * 保证用例之间无顺序依赖（H2 命名内存库在同一 JVM、同一 Spring 上下文内共享）。
 */
public abstract class DatabaseFixture {

    protected static final long INITIAL_RANGE_START = 0L;
    protected static final long INITIAL_RANGE_END = 1_000_000L;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM query_generation");
        jdbc.update("DELETE FROM purpose_migration_target");
        jdbc.update("DELETE FROM purpose_migration");
        jdbc.update("DELETE FROM purpose_catalog_entry");
        jdbc.update("DELETE FROM purpose_catalog_generation");
        seedInitialCatalog();
    }

    /**
     * 重新播种初始目录代次，与 {@code CatalogInitializer} 保持一致。
     */
    protected void seedInitialCatalog() {
        jdbc.update("INSERT INTO purpose_catalog_generation (catalog_generation) VALUES (1)");
        jdbc.update("INSERT INTO purpose_catalog_entry"
                        + " (catalog_generation, purpose, range_start, range_end, supersedes, status)"
                        + " VALUES (1, 'RESEARCH', ?, ?, NULL, 'ACTIVE')",
                INITIAL_RANGE_START, INITIAL_RANGE_END);
        jdbc.update("INSERT INTO purpose_catalog_entry"
                        + " (catalog_generation, purpose, range_start, range_end, supersedes, status)"
                        + " VALUES (1, 'PERSONALIZATION', ?, ?, NULL, 'ACTIVE')",
                INITIAL_RANGE_START, INITIAL_RANGE_END);
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
