package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 校验默认本地启动使用的主资源 H2 建表脚本 schema-h2.sql（MODE=MySQL）可独立执行，
 * 且包含既有表、改签前后继关联表与发布锁初始行；使用独立命名内存库，连接关闭即释放。
 */
class MainH2SchemaTest {

    @Test
    void mainH2SchemaCreatesAllTablesAndLocks() throws Exception {
        String url = "jdbc:h2:mem:main_schema_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));

            Set<String> tables = new LinkedHashSet<>();
            try (ResultSet rs = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
            assertThat(tables).contains(
                    "rail_day_plan",
                    "rail_plan_occupancy",
                    "idempotency_record",
                    "publish_lock",
                    "rail_plan_succession");

            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM publish_lock WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }

            // 前后继唯一约束存在：重复前驱/后继插入均失败
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO rail_day_plan"
                        + " (schedule_key, op_date, version, status, created_at, updated_at)"
                        + " VALUES ('P1', DATE '2026-09-23', 1, 'PUBLISHED', 0, 0)");
                st.executeUpdate("INSERT INTO rail_day_plan"
                        + " (schedule_key, op_date, version, status, created_at, updated_at)"
                        + " VALUES ('P2', DATE '2026-09-23', 1, 'DRAFT', 0, 0)");
                st.executeUpdate("INSERT INTO rail_plan_succession"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (1, 2, 0)");
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO rail_plan_succession"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (1, 1, 0)");
                org.assertj.core.api.Assertions.fail("重复前驱关联应被唯一约束拒绝");
            } catch (Exception expected) {
                assertThat(expected.getMessage()).containsAnyOf("Unique", "UNIQUE", "unique");
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO rail_plan_succession"
                        + " (predecessor_plan_id, successor_plan_id, created_at) VALUES (2, 2, 0)");
                org.assertj.core.api.Assertions.fail("重复后继关联应被唯一约束拒绝");
            } catch (Exception expected) {
                assertThat(expected.getMessage()).containsAnyOf("Unique", "UNIQUE", "unique");
            }
        }
    }
}
