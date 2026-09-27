package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 生产 schema.sql 建表验证：在独立命名的 H2 内存库（MODE=MySQL）上执行主资源中的完整 DDL，
 * 确保含中文 COMMENT 的语句与唯一约束在 MySQL 兼容模式下可直接建表。
 */
class SchemaSqlTest {

    @Test
    void productionSchemaExecutesOnH2MySqlModeWithAllTablesAndConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema-ddl-check;MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));

            List<String> tables = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                                 + " WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME")) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).containsExactly(
                    "CONSENT_GRANT", "CONSENT_RECORD", "CONSENT_SNAPSHOT",
                    "CONSENT_SNAPSHOT_ITEM", "IDEMPOTENCY_REQUEST", "RESIDENCY_REGION");

            // 唯一约束：同一主体/用途代次唯一、同代记录键唯一、requestId 唯一、区域代码唯一
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO residency_region (region_code, request_id, created_at)"
                        + " VALUES ('EU', 'r1', CURRENT_TIMESTAMP)");
                statement.execute("INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES ('s1', 'RESEARCH', 1, 'ACTIVE', 'g1')");
                statement.execute("INSERT INTO consent_record (subject_key, purpose, epoch, record_key,"
                        + " payload, request_id) VALUES ('s1', 'RESEARCH', 1, 'k1', 'p1', 'w1')");
            }
            assertDuplicateKey(connection,
                    "INSERT INTO residency_region (region_code, request_id, created_at)"
                            + " VALUES ('EU', 'r2', CURRENT_TIMESTAMP)");
            assertDuplicateKey(connection,
                    "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                            + " VALUES ('s1', 'RESEARCH', 1, 'ACTIVE', 'g2')");
            assertDuplicateKey(connection,
                    "INSERT INTO consent_record (subject_key, purpose, epoch, record_key, payload, request_id)"
                            + " VALUES ('s1', 'RESEARCH', 1, 'k1', 'p2', 'w2')");
        }
    }

    private void assertDuplicateKey(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("预期唯一约束冲突，但语句执行成功: " + sql);
        } catch (Exception expected) {
            // H2 MySQL 模式下重复键错误码 23505
            assertThat(expected.getMessage()).containsIgnoringCase("Unique index or primary key violation");
        }
    }
}
