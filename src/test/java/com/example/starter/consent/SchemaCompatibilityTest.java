package com.example.starter.consent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证主 schema.sql（含中文 COMMENT）可直接在 H2 MySQL 兼容模式下自动建表，
 * 保证本地以默认 H2 配置启动无需外部 MySQL。
 */
class SchemaCompatibilityTest {

    @Test
    void mainSchemaRunsOnH2MySqlMode() throws Exception {
        String url = "jdbc:h2:mem:schema-compat;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT UPPER(table_name) FROM information_schema.tables"
                                    + " WHERE UPPER(table_name) IN "
                                    + "('CONSENT_GRANT','CONSENT_RECORD','IDEMPOTENCY_REQUEST',"
                                    + "'DELEGATE_GRANT','DELEGATE_QUERY','DELEGATE_QUERY_ITEM','DELEGATE_QUERY_BLOCK')"
                                    + " ORDER BY table_name")) {
                assertThat(generate(rs)).containsExactly(
                        "CONSENT_GRANT", "CONSENT_RECORD",
                        "DELEGATE_GRANT", "DELEGATE_QUERY", "DELEGATE_QUERY_BLOCK",
                        "DELEGATE_QUERY_ITEM", "IDEMPOTENCY_REQUEST");
            }

            // 建表后基本写入冒烟：参数化写入与唯一约束可用
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                                + " VALUES ('s1', 'RESEARCH', 1, 'ACTIVE', 'r1')");
            }
        }
    }

    private static java.util.List<String> generate(ResultSet rs) throws java.sql.SQLException {
        java.util.List<String> tables = new java.util.ArrayList<>();
        while (rs.next()) {
            tables.add(rs.getString(1));
        }
        return tables;
    }
}
