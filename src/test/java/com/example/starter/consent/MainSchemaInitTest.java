package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 本地启动建表冒烟测试：直接在全新的嵌入式 H2（MODE=MySQL）上执行主运行时 schema.sql，
 * 验证自动初始化脚本（含 COMMENT ON 语句）可完整执行，并具备本题要求的全部表与列。
 */
class MainSchemaInitTest {

    @Test
    void mainSchemaSqlInitializesOnEmbeddedH2() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:main-schema-smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));

            List<String> tables = List.of("consent_grant", "consent_delegation",
                    "consent_record", "idempotency_request");
            for (String table : tables) {
                Integer count = null;
                try (var ps = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables"
                        + " WHERE LOWER(table_name) = ?")) {
                    ps.setString(1, table);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) {
                            count = rs.getInt(1);
                        }
                    }
                }
                assertThat(count).isEqualTo(1);
            }

            try (var ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE table_name = 'consent_delegation' AND column_name = 'delegation_key'");
                    var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE table_name = 'consent_record' AND column_name = 'evaluated_at'");
                    var rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
