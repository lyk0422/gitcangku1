package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * 本地入口主脚本 src/main/resources/schema.sql 的 H2 冒烟验证：
 * 在独立命名内存库（MODE=MySQL）上执行主脚本，确认自动建表与合成站台数据可用。
 * 主脚本处于 test classpath 时会被测试同名资源遮蔽，因此直接按文件路径读取。
 */
class MainSchemaSmokeTest {

    @Test
    void mainSchemaRunsOnH2AndSeedsSyntheticPlatforms() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/schema.sql"));
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:mainschema_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "")) {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT platform_id, version, max_length_m, supported_power_types,"
                                    + " accessible FROM rail_platform_capability ORDER BY platform_id")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("platform_id")).isEqualTo("PLT-180");
                assertThat(rs.getInt("version")).isEqualTo(1);
                assertThat(rs.getInt("max_length_m")).isEqualTo(180);
                assertThat(rs.getString("supported_power_types")).isEqualTo("AC25KV");
                assertThat(rs.getBoolean("accessible")).isTrue();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("platform_id")).isEqualTo("PLT-220");
                assertThat(rs.getInt("max_length_m")).isEqualTo(220);
                assertThat(rs.next()).isFalse();
            }
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM publish_lock")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            // 编组列存在且计划可按新结构写入
            try (Statement st = conn.createStatement()) {
                int inserted = st.executeUpdate("INSERT INTO rail_day_plan"
                        + " (schedule_key, op_date, version, status, consist_length_m,"
                        + " power_supply_type, accessible_required, created_at, updated_at)"
                        + " VALUES ('SMOKE-1', DATE '2026-09-23', 1, 'DRAFT', 200, 'AC25KV', TRUE, 0, 0)");
                assertThat(inserted).isEqualTo(1);
            }
        }
    }
}
