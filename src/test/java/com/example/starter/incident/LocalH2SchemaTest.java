package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 验证本地启动入口所用的真实资源：主 application.yaml 默认 H2（MODE=MySQL）连接串
 * 与 classpath:schema-h2.sql 能在无外部数据库的情况下自动建表，且两域唯一约束、
 * 演练批次墓碑等关键约束在 H2 MySQL 兼容模式下真实生效（非 mock）。
 * 使用独立命名内存库，连接关闭后即释放，不与测试上下文共享。
 */
class LocalH2SchemaTest {

    @Test
    void localH2Schema_bootstrapsAndEnforcesDomainConstraints() throws Exception {
        String url = "jdbc:h2:mem:local_schema_verify;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-h2.sql"));

            List<String> expected = List.of("incidents", "incident_actions", "incident_transfers",
                    "incident_status_history", "incident_dependencies", "incident_escalations",
                    "incident_notifications", "command_keys", "drill_batches");
            try (Statement st = conn.createStatement()) {
                for (String table : expected) {
                    try (ResultSet rs = st.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = '"
                                    + table.toLowerCase() + "'")) {
                        rs.next();
                        assertThat(rs.getInt(1)).as("表 %s 应被自动创建", table).isEqualTo(1);
                    }
                }
            }

            try (Statement st = conn.createStatement()) {
                // 同一 incident_key 在 REAL 与 DRILL 两域可各存一行而不冲突
                st.executeUpdate("INSERT INTO incidents (incident_key, domain, drill_batch_key, severity,"
                        + " summary, reporter, status, commander, created_at, updated_at)"
                        + " VALUES ('K1','REAL',NULL,'S1','r','s','REPORTED',NULL,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP)");
                st.executeUpdate("INSERT INTO incidents (incident_key, domain, drill_batch_key, severity,"
                        + " summary, reporter, status, commander, created_at, updated_at)"
                        + " VALUES ('K1','DRILL','B1','S1','r','s','REPORTED',NULL,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP)");
                // 域内重复键必须被唯一约束拒绝
                boolean duplicateRejected = false;
                try {
                    st.executeUpdate("INSERT INTO incidents (incident_key, domain, severity, summary,"
                            + " reporter, status, created_at, updated_at)"
                            + " VALUES ('K1','REAL','S1','r','s','REPORTED',CURRENT_TIMESTAMP,"
                            + "CURRENT_TIMESTAMP)");
                } catch (Exception e) {
                    duplicateRejected = true;
                }
                assertThat(duplicateRejected).as("域内 incident_key 重复必须被唯一约束拒绝").isTrue();

                // command_keys 两域键空间独立
                st.executeUpdate("INSERT INTO command_keys (command_key, domain, operation, request_hash,"
                        + " created_at) VALUES ('CK','REAL','takeover','x',CURRENT_TIMESTAMP)");
                st.executeUpdate("INSERT INTO command_keys (command_key, domain, operation, request_hash,"
                        + " created_at) VALUES ('CK','DRILL','takeover','x',CURRENT_TIMESTAMP)");

                // 批次墓碑：batch_key 全局唯一
                st.executeUpdate("INSERT INTO drill_batches (batch_key, created_at)"
                        + " VALUES ('B1',CURRENT_TIMESTAMP)");
                boolean batchRejected = false;
                try {
                    st.executeUpdate("INSERT INTO drill_batches (batch_key, created_at)"
                            + " VALUES ('B1',CURRENT_TIMESTAMP)");
                } catch (Exception e) {
                    batchRejected = true;
                }
                assertThat(batchRejected).as("drill batch_key 必须全局唯一").isTrue();
            }
        }
    }
}
