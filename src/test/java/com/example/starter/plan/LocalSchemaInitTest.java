package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 校验本地默认启动使用的主资源 schema.sql（H2，MODE=MySQL）可在空 H2 库上
 * 自动建表成功——测试 classpath 下的 schema.sql 会遮蔽主资源，故这里直接
 * 读取 src/main/resources/schema.sql 文件在独立命名内存库执行。
 */
class LocalSchemaInitTest {

    @Test
    void mainSchemaSqlInitializesOnH2MysqlMode() throws Exception {
        Path schema = Path.of("src", "main", "resources", "schema.sql");
        assertThat(Files.exists(schema)).as("主资源 schema.sql 存在: %s", schema).isTrue();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:schema_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0",
                "sa", "")) {
            ScriptUtils.executeSqlScript(conn, new FileSystemResource(schema));

            List<String> expectedTables = Arrays.asList(
                    "rail_day_plan", "rail_plan_occupancy", "rail_plan_revision",
                    "idempotency_record", "publish_lock");
            for (String table : expectedTables) {
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    int count = rs.getInt(1);
                    // publish_lock 初始化时插入固定锁行，其余表为空
                    int expected = table.equals("publish_lock") ? 1 : 0;
                    assertThat(count).as("表 %s 初始化行数", table).isEqualTo(expected);
                }
            }

            // 改签前后继唯一约束：前驱、后继各最多一条
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO rail_day_plan"
                        + " (schedule_key, op_date, version, status, created_at, updated_at)"
                        + " VALUES ('a', DATE '2026-09-22', 1, 'CANCELLED', 0, 0),"
                        + " ('b', DATE '2026-09-22', 1, 'PUBLISHED', 0, 0)");
                st.execute("INSERT INTO rail_plan_revision"
                        + " (predecessor_plan_id, successor_plan_id, created_at)"
                        + " VALUES (1, 2, 0)");
            }
            try (Statement st = conn.createStatement()) {
                st.execute("INSERT INTO rail_plan_revision"
                        + " (predecessor_plan_id, successor_plan_id, created_at)"
                        + " VALUES (1, 2, 1)");
                org.assertj.core.api.Assertions.fail("同一前驱出现两条关联应违反唯一约束");
            } catch (java.sql.SQLException expected) {
                // 唯一约束生效
            }
        }
    }
}
