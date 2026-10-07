package com.example.starter.playout;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地入口合成种子数据脚本（data-h2.sql）验证：在独立的 H2（MODE=MySQL）内存库上
 * 先建表再执行种子脚本，校验合成数据内容与重复执行的幂等性。不触碰标准测试库。
 */
class LocalSeedDataTest {

    @Test
    void seedScriptInitializesSyntheticDataIdempotently() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:seed_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data-h2.sql"));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playout_asset", Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playout_channel", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playout_grant", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_grant WHERE channel_id = 'demo-ch'"
                        + " AND asset_id IN ('demo-movie', 'demo-news') AND revoked = 0"
                        + " AND valid_from_ms < valid_to_ms", Long.class))
                .isEqualTo(2L);

        // 重复执行（如同 JVM 内重连已有内存库）：数据不翻倍
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("data-h2.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playout_asset", Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playout_grant", Long.class))
                .isEqualTo(2L);
    }
}
