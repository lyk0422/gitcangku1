package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 验证主 schema.sql（含中文 COMMENT）能在嵌入式 H2 的 MySQL 兼容模式下直接初始化，
 * 从而保证本地无需外部 MySQL 即可启动（仅保证同一 JVM 内状态）。
 */
class SchemaH2CompatibilityTest {

    @Test
    void mainSchemaInitializesOnH2MySqlMode() throws Exception {
        String url = "jdbc:h2:mem:schema-compat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.execute("SELECT catalog_generation, purpose, range_start, range_end,"
                        + " supersedes, status FROM purpose_catalog_entry")).isTrue();
                try (ResultSet rs = statement.getResultSet()) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    // 建表成功且关键列可读即说明结构与 COMMENT 语法被 H2 接受
                    assertThat(rows).isZero();
                }
                statement.execute("INSERT INTO purpose_catalog_generation (catalog_generation) VALUES (1)");
                statement.execute("INSERT INTO purpose_catalog_entry"
                        + " (catalog_generation, purpose, range_start, range_end, supersedes, status)"
                        + " VALUES (1, 'RESEARCH', 0, 100, NULL, 'ACTIVE')");
                statement.execute("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES ('s', 'RESEARCH', 1, 'ACTIVE', 'r')");
                statement.execute("INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload,"
                        + " record_attribute, request_id) VALUES ('s', 'RESEARCH', 1, 'k', 'p', 1, 'r')");
                statement.execute("INSERT INTO query_generation (catalog_generation, status) VALUES (1, 'ACTIVE')");
                try (ResultSet rs = statement.executeQuery("SELECT query_generation FROM query_generation")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isPositive();
                }
            }
        }
    }
}
