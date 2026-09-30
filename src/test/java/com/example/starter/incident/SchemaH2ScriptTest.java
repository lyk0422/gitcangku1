package com.example.starter.incident;

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
 * 验证默认本地运行入口使用的 schema-h2.sql 可在 H2 MySQL 兼容模式下完整执行，
 * 并支撑关键约束：事件键唯一、每事件至多一条升级记录、条件更新只作用于 OPEN。
 * 使用独立命名内存库，连接关闭即释放，不依赖外部数据库。
 */
class SchemaH2ScriptTest {

    @Test
    void h2Schema_executesAndEnforcesConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at, deadline_at) VALUES"
                        + " ('IK-1','S1','s','r','COMMANDING','alice','" + Timestamp.from(now) + "',"
                        + "'" + Timestamp.from(now) + "','" + Timestamp.from(now.plusSeconds(300)) + "')");

                // incident_key 唯一约束
                boolean duplicateRejected = false;
                try {
                    st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                            + " commander, created_at, updated_at) VALUES"
                            + " ('IK-1','S1','s','r','REPORTED',NULL,'" + Timestamp.from(now) + "',"
                            + "'" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateRejected = true;
                }
                assertThat(duplicateRejected).isTrue();

                st.execute("INSERT INTO incident_escalations (incident_id, deadline_at, triggered_at,"
                        + " triggered_commander, status, note, acknowledged_by, acknowledged_at,"
                        + " created_at, updated_at) VALUES (1,'" + Timestamp.from(now.plusSeconds(300))
                        + "','" + Timestamp.from(now) + "','alice','OPEN',NULL,NULL,NULL,'"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");

                // 每事件至多一条升级记录
                boolean secondEscalationRejected = false;
                try {
                    st.execute("INSERT INTO incident_escalations (incident_id, deadline_at,"
                            + " triggered_at, triggered_commander, status, note, acknowledged_by,"
                            + " acknowledged_at, created_at, updated_at) VALUES (1,'"
                            + Timestamp.from(now.plusSeconds(300)) + "','" + Timestamp.from(now)
                            + "','alice','OPEN',NULL,NULL,NULL,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    secondEscalationRejected = true;
                }
                assertThat(secondEscalationRejected).isTrue();

                // 条件更新只作用于 OPEN：CANCELLED 后确认更新 0 行
                int cancelled = st.executeUpdate("UPDATE incident_escalations SET status='CANCELLED'"
                        + " WHERE incident_id = 1 AND status = 'OPEN'");
                assertThat(cancelled).isEqualTo(1);
                int lateAck = st.executeUpdate("UPDATE incident_escalations SET status='ACKNOWLEDGED'"
                        + " WHERE id = 1 AND status = 'OPEN'");
                assertThat(lateAck).isZero();

                try (ResultSet rs = st.executeQuery(
                        "SELECT status, deadline_at FROM incident_escalations WHERE incident_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("CANCELLED");
                    assertThat(rs.getTimestamp("deadline_at").toInstant())
                            .isEqualTo(now.plusSeconds(300));
                }

                // 处置任务：(incident_id, task_key) 唯一
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code, title,"
                        + " status, created_by, created_at, updated_at) VALUES (1,'T-1','G','t',"
                        + "'OPEN','alice','" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");
                boolean duplicateTaskRejected = false;
                try {
                    st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code,"
                            + " title, status, created_by, created_at, updated_at) VALUES"
                            + " (1,'T-1','G','t2','OPEN','alice','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateTaskRejected = true;
                }
                assertThat(duplicateTaskRejected).isTrue();

                // 阻塞边：(task_id, blocker_incident_id) 唯一
                st.execute("INSERT INTO incident_task_blockers (task_id, blocker_incident_id,"
                        + " created_at) VALUES (1,1,'" + Timestamp.from(now) + "')");
                boolean duplicateBlockerRejected = false;
                try {
                    st.execute("INSERT INTO incident_task_blockers (task_id, blocker_incident_id,"
                            + " created_at) VALUES (1,1,'" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateBlockerRejected = true;
                }
                assertThat(duplicateBlockerRejected).isTrue();

                // 升级阶梯：阈值版本键唯一
                st.execute("INSERT INTO ladder_versions (version_key, threshold_1, threshold_2,"
                        + " threshold_3, threshold_4, threshold_5, cooldown_minutes, created_by,"
                        + " created_at) VALUES ('V1',10,20,30,40,50,30,'admin','"
                        + Timestamp.from(now) + "')");
                boolean duplicateVersionRejected = false;
                try {
                    st.execute("INSERT INTO ladder_versions (version_key, threshold_1, threshold_2,"
                            + " threshold_3, threshold_4, threshold_5, cooldown_minutes, created_by,"
                            + " created_at) VALUES ('V1',1,2,3,4,5,0,'admin','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateVersionRejected = true;
                }
                assertThat(duplicateVersionRejected).isTrue();

                // 阶梯状态：每事件至多一行
                st.execute("INSERT INTO incident_ladder_states (incident_id, current_level, last_score,"
                        + " threshold_version_id, last_promoted_at, created_at, updated_at) VALUES"
                        + " (1,4,45,1,'" + Timestamp.from(now) + "','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateStateRejected = false;
                try {
                    st.execute("INSERT INTO incident_ladder_states (incident_id, current_level,"
                            + " last_score, threshold_version_id, last_promoted_at, created_at,"
                            + " updated_at) VALUES (1,2,20,1,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateStateRejected = true;
                }
                assertThat(duplicateStateRejected).isTrue();

                // 分数证据允许同事件多条，evaluated_level 可空（未进入阶梯）
                st.execute("INSERT INTO incident_ladder_scores (incident_id, score,"
                        + " threshold_version_id, evaluated_level, submitted_by, occurred_at,"
                        + " created_at) VALUES (1,5,1,NULL,'alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_ladder_scores (incident_id, score,"
                        + " threshold_version_id, evaluated_level, submitted_by, occurred_at,"
                        + " created_at) VALUES (1,45,1,4,'alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");

                // 条件降级：等级匹配时降一级（4→3），再次以 expected=4 调用更新 0 行
                int firstDemote = st.executeUpdate("UPDATE incident_ladder_states s SET"
                        + " s.current_level = s.current_level - 1 WHERE s.incident_id = 1"
                        + " AND s.current_level = 4 AND EXISTS (SELECT 1 FROM incidents i"
                        + " WHERE i.id = s.incident_id AND i.status <> 'CLOSED')");
                assertThat(firstDemote).isEqualTo(1);
                int secondDemoteSameSnapshot = st.executeUpdate("UPDATE incident_ladder_states s SET"
                        + " s.current_level = s.current_level - 1 WHERE s.incident_id = 1"
                        + " AND s.current_level = 4 AND EXISTS (SELECT 1 FROM incidents i"
                        + " WHERE i.id = s.incident_id AND i.status <> 'CLOSED')");
                assertThat(secondDemoteSameSnapshot).isZero();
                try (ResultSet rs = st.executeQuery(
                        "SELECT current_level, last_promoted_at FROM incident_ladder_states"
                                + " WHERE incident_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("current_level")).isEqualTo(3);
                    assertThat(rs.getTimestamp("last_promoted_at").toInstant()).isEqualTo(now);
                }

                // 历史只追加：同事件可插入多条，from_level 首条可空
                st.execute("INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                        + " to_level, score, threshold_version_id, operator, cooldown_since,"
                        + " occurred_at) VALUES (1,'PROMOTE',NULL,4,45,1,'alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_ladder_history (incident_id, change_type, from_level,"
                        + " to_level, score, threshold_version_id, operator, cooldown_since,"
                        + " occurred_at) VALUES (1,'DEMOTE',4,3,45,1,'bob','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
            }
        }
    }
}
