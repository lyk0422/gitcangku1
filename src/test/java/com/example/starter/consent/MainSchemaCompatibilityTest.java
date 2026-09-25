package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 主 schema.sql 兼容性测试：本地运行默认使用 H2 内存库（MODE=MySQL），
 * 此处验证带 MySQL COMMENT 的主建表脚本可在 H2 兼容模式下完整执行。
 */
class MainSchemaCompatibilityTest {

    @Test
    void mainSchemaExecutesOnH2MysqlMode() throws Exception {
        // H2 为运行时依赖，通过 DriverManager 经 ServiceLoader 加载驱动
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:schema-check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/schema.sql"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String table : new String[]{"consent_grant", "consent_record", "idempotency_request",
                "export_snapshot", "export_snapshot_purpose", "export_snapshot_record"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(count).as("表 %s 应由主 schema.sql 创建", table).isEqualTo(1);
        }
    }
}
