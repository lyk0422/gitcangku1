package com.example.starter.germination;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地入口冒烟测试：应用上下文以 H2 内存库启动，schema.sql 自动建表、
 * data.sql 初始化合成批次，且数据库运行在 MySQL 兼容模式。
 */
@SpringBootTest
class ApplicationStartupH2SmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextStartsWithH2MySqlModeAndSeedBatches() {
        String mode = jdbc.queryForObject(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'",
                String.class);
        assertThat(mode).containsIgnoringCase("MySQL");

        List<String> batches = jdbc.queryForList(
                "SELECT batch_no FROM seed_batch ORDER BY batch_no", String.class);
        assertThat(batches).containsExactly(
                "BATCH-2026-1001", "BATCH-2026-1002", "BATCH-2026-1003");

        // 关键约束与表均已自动创建
        Integer tableCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_NAME IN ('SEED_BATCH', 'GERMINATION_TEST', 'GERMINATION_CERTIFICATE',
                                     'BATCH_RELEASE', 'IDEMPOTENT_REQUEST')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(5);
    }
}
