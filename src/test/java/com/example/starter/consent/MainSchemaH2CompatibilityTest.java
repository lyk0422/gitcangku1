package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 本地入口（H2 内存库 MODE=MySQL）使用主 schema.sql 自动建表的兼容性验证：
 * 在独立命名库上实际执行主建表语句，确认列级 COMMENT 与表结构在 H2 上可用。
 */
class MainSchemaH2CompatibilityTest {

    @Test
    void mainSchemaExecutesOnH2MySqlMode() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));
        String url = "jdbc:h2:mem:schema-check-" + UUID.randomUUID() + ";MODE=MySQL";
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
            for (String table : new String[]{"CONSENT_GRANT", "CONSENT_RECORD",
                    "IDEMPOTENCY_REQUEST", "COHORT_AGGREGATE_SNAPSHOT"}) {
                try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
                    assertThat(rs.next()).as("表 %s 应由主 schema.sql 创建", table).isTrue();
                }
            }
            // 数值记录新列存在
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, "CONSENT_RECORD", "NUM_VALUE")) {
                assertThat(rs.next()).isTrue();
            }
        }
    }
}
