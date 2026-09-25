package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 本地启动入口的建表验证：主 resources 下的 schema.sql（MySQL 方言）必须能在
 * 嵌入式 H2（MODE=MySQL）上完整执行，保证本地运行无需外部数据库即可自动建表。
 * 使用独立命名内存库，结束后释放，不影响其他用例。
 */
class MainSchemaH2CompatibilityTest {

    @Test
    void mainSchemaExecutesOnH2MysqlMode() throws Exception {
        String dbName = "schema_check_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(conn,
                    new FileSystemResource("src/main/resources/schema.sql"));

            try (Statement st = conn.createStatement()) {
                // 发布锁初始化数据已写入
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM publish_lock")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                // 区段等级表可写入并读回
                st.executeUpdate("INSERT INTO rail_section (section_id, priority, created_at)"
                        + " VALUES ('SEC-SMOKE', 5, 1)");
                try (ResultSet rs = st.executeQuery(
                        "SELECT priority FROM rail_section WHERE section_id = 'SEC-SMOKE'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(5);
                }
                // 抢占记录与时隙表可写入并读回
                st.executeUpdate("INSERT INTO rail_preemption (preempting_plan_id,"
                        + " preempting_schedule_key, preempted_plan_id, preempted_schedule_key,"
                        + " preempting_level, preempted_level, created_at)"
                        + " VALUES (2, 'SCH-H', 1, 'SCH-L', 5, 1, 1)");
                st.executeUpdate("INSERT INTO rail_preemption_slot (preemption_id, section_id,"
                        + " section_level, start_utc, end_utc) VALUES (1, 'SEC-SMOKE', 5, 10, 20)");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rail_preemption_slot"
                        + " WHERE section_id = 'SEC-SMOKE' AND start_utc < 20 AND 10 < end_utc")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                // PREEMPTED 状态值长度在 status 列容量内
                st.executeUpdate("INSERT INTO rail_day_plan (schedule_key, op_date, version,"
                        + " status, created_at, updated_at)"
                        + " VALUES ('SCH-L', DATE '2026-09-22', 1, 'PREEMPTED', 1, 1)");
                try (ResultSet rs = st.executeQuery(
                        "SELECT status FROM rail_day_plan WHERE schedule_key = 'SCH-L'")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("PREEMPTED");
                }
            } finally {
                // 释放本用例的命名内存库
                try (Statement st = conn.createStatement()) {
                    st.execute("DROP ALL OBJECTS");
                }
            }
        }
    }
}
