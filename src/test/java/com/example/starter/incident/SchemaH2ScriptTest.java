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
            }
        }
    }

    @Test
    void h2Schema_resourceTables_enforceConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_resource_check;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
                + "DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter,"
                        + " status, commander, created_at, updated_at) VALUES"
                        + " ('IK-R','S1','s','r','COMMANDING','alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code, title,"
                        + " status, created_by, created_at, updated_at) VALUES (1,'T-1','G','t',"
                        + "'OPEN','alice','" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");

                // 资源租约：每任务至多一条当前租约（uk_assignment_task）
                st.execute("INSERT INTO task_resource_assignments (task_id, incident_id,"
                        + " resource_key, authorization_version, assigned_by, assigned_at,"
                        + " expires_at, created_at, updated_at) VALUES (1,1,'RES-1',1,'alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now.plusSeconds(300))
                        + "','" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean duplicateAssignmentRejected = false;
                try {
                    st.execute("INSERT INTO task_resource_assignments (task_id, incident_id,"
                            + " resource_key, authorization_version, assigned_by, assigned_at,"
                            + " expires_at, created_at, updated_at) VALUES (1,1,'RES-2',1,'alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now.plusSeconds(300))
                            + "','" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateAssignmentRejected = true;
                }
                assertThat(duplicateAssignmentRejected).isTrue();

                // 资源串行锁：resource_key 主键唯一
                st.execute("INSERT INTO resource_locks (resource_key) VALUES ('RES-1')");
                boolean duplicateLockRejected = false;
                try {
                    st.execute("INSERT INTO resource_locks (resource_key) VALUES ('RES-1')");
                } catch (Exception e) {
                    duplicateLockRejected = true;
                }
                assertThat(duplicateLockRejected).isTrue();

                // 授权证据：旧/新版本与拦截动作可空，按 id 排序
                st.execute("INSERT INTO resource_authorization_events (event_type, task_id,"
                        + " incident_id, task_key, resource_key, old_authorization_version,"
                        + " new_authorization_version, actor, attempted_action, occurred_at)"
                        + " VALUES ('ASSIGN',1,1,'T-1','RES-1',NULL,1,'alice',NULL,'"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO resource_authorization_events (event_type, task_id,"
                        + " incident_id, task_key, resource_key, old_authorization_version,"
                        + " new_authorization_version, actor, attempted_action, occurred_at)"
                        + " VALUES ('EXECUTION_BLOCKED',1,1,'T-1','RES-1',1,NULL,'alice',"
                        + "'COMPLETE','" + Timestamp.from(now.plusSeconds(300)) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT event_type, old_authorization_version, new_authorization_version,"
                                + " attempted_action FROM resource_authorization_events"
                                + " ORDER BY id")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("event_type")).isEqualTo("ASSIGN");
                    assertThat(rs.getObject("old_authorization_version")).isNull();
                    assertThat(rs.getLong("new_authorization_version")).isEqualTo(1L);
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("event_type")).isEqualTo("EXECUTION_BLOCKED");
                    assertThat(rs.getObject("new_authorization_version")).isNull();
                    assertThat(rs.getString("attempted_action")).isEqualTo("COMPLETE");
                }

                // 执行日志：note 可空
                st.execute("INSERT INTO task_execution_logs (task_id, incident_id, action, note,"
                        + " actor, occurred_at) VALUES (1,1,'START',NULL,'alice','"
                        + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT action, note FROM task_execution_logs WHERE task_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("action")).isEqualTo("START");
                    assertThat(rs.getString("note")).isNull();
                }
            }
        }
    }
}
