package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.incident.ResidualRisk.Fraction;
import com.example.starter.incident.ResidualRisk.Ratio;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 验证本地入口的合成数据脚本 data-h2.sql 可在 schema-h2.sql 之上完整执行，
 * 且合成场景与题干一致：R=100、L=20，两条 1/2 屏障（任务 DONE）残余 25 超限，
 * 1/5 屏障任务仍 OPEN 不计入。使用独立命名内存库，连接关闭即释放。
 */
class DemoDataH2ScriptTest {

    @Test
    void demoData_loadsAndMatchesSyntheticScenario() throws Exception {
        String url = "jdbc:h2:mem:demo_data_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "");
             Statement st = con.createStatement()) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));
            ScriptUtils.executeSqlScript(con, new ClassPathResource("data-h2.sql"));

            try (ResultSet rs = st.executeQuery("SELECT initial_risk, residual_limit, version,"
                    + " contained_at FROM incident_risk_models WHERE incident_id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("initial_risk")).isEqualTo(100);
                assertThat(rs.getInt("residual_limit")).isEqualTo(20);
                assertThat(rs.getLong("version")).isEqualTo(4);
                assertThat(rs.getTimestamp("contained_at")).isNull();
            }

            // 按题干规则在 Java 侧核算：仅任务 DONE 且未撤销的屏障计入
            List<Ratio> effective = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT b.reduce_p, b.reduce_q, b.revoked, t.status FROM incident_risk_barriers b"
                            + " JOIN incident_tasks t ON t.incident_id = 1 AND t.task_key = b.task_key"
                            + " WHERE b.model_id = 1 ORDER BY b.id")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    if (!rs.getBoolean("revoked") && "DONE".equals(rs.getString("status"))) {
                        effective.add(new Ratio(rs.getInt("reduce_p"), rs.getInt("reduce_q")));
                    }
                }
                assertThat(count).isEqualTo(3);
            }
            Fraction residual = ResidualRisk.residual(100, effective);
            assertThat(residual.asString()).isEqualTo("25/1");
            assertThat(ResidualRisk.withinLimit(residual, 20)).isFalse();
        }
    }
}
