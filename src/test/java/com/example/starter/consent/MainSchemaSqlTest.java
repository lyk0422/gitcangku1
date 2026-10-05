package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 本地入口使用 src/main/resources/schema.sql 在 H2 内存库（MODE=MySQL）上自动建表，
 * 本测试直接对该脚本做一次真实执行，验证无需外部 MySQL 即可启动。
 */
class MainSchemaSqlTest {

    @Test
    void mainSchemaExecutesOnH2MysqlMode() throws Exception {
        String url = "jdbc:h2:mem:schema-compat-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("src/main/resources/schema.sql"));

            for (String table : new String[]{
                    "CONSENT_GRANT", "CONSENT_RECORD", "IDEMPOTENCY_REQUEST",
                    "STRUCTURED_RECORD", "FIELD_RESTRICTION"}) {
                try (ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
                    assertThat(rs.next()).as("表 %s 应已创建", table).isTrue();
                }
            }
        }
    }
}
