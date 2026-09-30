package com.example.starter.archival;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：真实内存库（MODE=MySQL）、真实事务与唯一约束；每个用例前清理业务数据。
 */
@SpringBootTest
abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        // 按外键依赖顺序清理，全部使用参数化/固定 DDL，不依赖外部 MySQL 或容器。
        jdbc.update("DELETE FROM treatment_round");
        jdbc.update("DELETE FROM batch_rejection");
        jdbc.update("DELETE FROM batch_member");
        jdbc.update("DELETE FROM page_occupation");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM deacidification_batch");
        jdbc.update("DELETE FROM archive_page");
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
