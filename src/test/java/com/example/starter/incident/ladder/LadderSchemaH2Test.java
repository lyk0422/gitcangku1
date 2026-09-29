package com.example.starter.incident.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 验证本地运行入口的 schema-h2.sql 在 H2（MODE=MySQL）下完整执行，并支撑升级阶梯关键约束：
 * 版本键唯一、阈值严格递增 CHECK、冷却范围 CHECK、事件阶梯态主键唯一、
 * 降级条件更新只作用于锚定等级（并发降级只生效一次）。使用独立命名内存库，连接关闭即释放。
 */
class LadderSchemaH2Test {

    @Test
    void ladderTables_enforceConstraintsAndConditionalDemote() throws Exception {
        String url = "jdbc:h2:mem:schema_ladder_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));
            Instant now = Instant.parse("2026-09-29T00:00:00Z");
            try (Statement st = con.createStatement()) {
                // 合法版本
                st.execute("INSERT INTO escalation_threshold_versions (version_key, threshold_1,"
                        + " threshold_2, threshold_3, threshold_4, threshold_5, cooldown_minutes,"
                        + " note, created_by, created_at) VALUES ('V1',0,10,30,60,100,10,NULL,'admin','"
                        + Timestamp.from(now) + "')");

                // 版本键唯一
                assertThat(insertFails(st, "INSERT INTO escalation_threshold_versions (version_key,"
                        + " threshold_1, threshold_2, threshold_3, threshold_4, threshold_5,"
                        + " cooldown_minutes, created_by, created_at) VALUES ('V1',0,10,30,60,101,"
                        + "10,'admin','" + Timestamp.from(now) + "')")).isTrue();

                // 阈值非严格递增被 CHECK 拒绝
                assertThat(insertFails(st, "INSERT INTO escalation_threshold_versions (version_key,"
                        + " threshold_1, threshold_2, threshold_3, threshold_4, threshold_5,"
                        + " cooldown_minutes, created_by, created_at) VALUES ('BAD',0,10,10,60,100,"
                        + "10,'admin','" + Timestamp.from(now) + "')")).isTrue();
                // 冷却越界被 CHECK 拒绝
                assertThat(insertFails(st, "INSERT INTO escalation_threshold_versions (version_key,"
                        + " threshold_1, threshold_2, threshold_3, threshold_4, threshold_5,"
                        + " cooldown_minutes, created_by, created_at) VALUES ('BAD2',0,10,30,60,100,"
                        + "527041,'admin','" + Timestamp.from(now) + "')")).isTrue();

                // 事件与阶梯态
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES ('IK-1','S2','s','r',"
                        + "'COMMANDING','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_ladder_state (incident_id, score, current_level,"
                        + " threshold_version_id, last_escalated_at, last_escalated_by, entered_at,"
                        + " updated_at, updated_by) VALUES (1,60,4,1,'" + Timestamp.from(now)
                        + "','alice','" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "','alice')");
                // 每事件至多一行
                assertThat(insertFails(st, "INSERT INTO incident_ladder_state (incident_id, score,"
                        + " current_level, threshold_version_id, last_escalated_at,"
                        + " last_escalated_by, entered_at, updated_at, updated_by) VALUES (1,60,4,1,'"
                        + Timestamp.from(now) + "','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "','alice')")).isTrue();
                // 等级越界 CHECK
                assertThat(insertFails(st, "INSERT INTO incident_ladder_state (incident_id, score,"
                        + " current_level, threshold_version_id, last_escalated_at,"
                        + " last_escalated_by, entered_at, updated_at, updated_by) VALUES (2,60,6,1,'"
                        + Timestamp.from(now) + "','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "','alice')")).isTrue();

                // 条件降级：锚定当前等级 4 才生效
                int first = st.executeUpdate("UPDATE incident_ladder_state SET current_level = 3"
                        + " WHERE incident_id = 1 AND current_level = 4");
                assertThat(first).isEqualTo(1);
                // 并发/重复降级：锚定 4 已不匹配，0 行生效
                int second = st.executeUpdate("UPDATE incident_ladder_state SET current_level = 3"
                        + " WHERE incident_id = 1 AND current_level = 4");
                assertThat(second).isZero();

                // 历史行可插入、可稳定排序读取，from_level/from_score 允许为空
                st.execute("INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                        + " to_level, from_score, to_score, threshold_version_id,"
                        + " threshold_version_key, actor, cooldown_start_at, command_key, occurred_at)"
                        + " VALUES (1,'INIT',NULL,4,NULL,60,1,'V1','alice','" + Timestamp.from(now)
                        + "','CK-1','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                        + " to_level, from_score, to_score, threshold_version_id,"
                        + " threshold_version_key, actor, cooldown_start_at, command_key, occurred_at)"
                        + " VALUES (1,'DEMOTE',4,3,60,60,1,'V1','bob','" + Timestamp.from(now)
                        + "','CK-2','" + Timestamp.from(now.plusSeconds(1)) + "')");
                try (ResultSet rs = st.executeQuery("SELECT change_type, from_level, from_score"
                        + " FROM incident_ladder_history WHERE incident_id = 1 ORDER BY id")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("change_type")).isEqualTo("INIT");
                    assertThat(rs.getObject("from_level")).isNull();
                    assertThat(rs.getObject("from_score")).isNull();
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("change_type")).isEqualTo("DEMOTE");
                    assertThat(rs.getInt("from_level")).isEqualTo(4);
                }
            }
        }
    }

    private static boolean insertFails(Statement st, String sql) {
        try {
            st.execute(sql);
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
