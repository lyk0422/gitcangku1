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

                // 指令计划：同一计划内 step_key、step_index 唯一；条件更新只在当前状态生效
                st.execute("INSERT INTO command_plans (incident_id, status, version, created_by,"
                        + " created_at, updated_at) VALUES (1,'RUNNING',0,'alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO command_plan_steps (plan_id, incident_id, step_index,"
                        + " step_key, effect_desc, compensation_desc, status, applied_by,"
                        + " applied_at, compensated_by, compensated_at, created_at, updated_at)"
                        + " VALUES (1,1,1,'S1','效果','补偿','PENDING',NULL,NULL,NULL,NULL,'"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean dupStepKeyRejected = false;
                try {
                    st.execute("INSERT INTO command_plan_steps (plan_id, incident_id, step_index,"
                            + " step_key, effect_desc, compensation_desc, status, applied_by,"
                            + " applied_at, compensated_by, compensated_at, created_at, updated_at)"
                            + " VALUES (1,1,2,'S1','x','y','PENDING',NULL,NULL,NULL,NULL,'"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    dupStepKeyRejected = true;
                }
                assertThat(dupStepKeyRejected).isTrue();
                boolean dupStepIndexRejected = false;
                try {
                    st.execute("INSERT INTO command_plan_steps (plan_id, incident_id, step_index,"
                            + " step_key, effect_desc, compensation_desc, status, applied_by,"
                            + " applied_at, compensated_by, compensated_at, created_at, updated_at)"
                            + " VALUES (1,1,1,'S2','x','y','PENDING',NULL,NULL,NULL,NULL,'"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    dupStepIndexRejected = true;
                }
                assertThat(dupStepIndexRejected).isTrue();

                // PENDING → APPLIED 条件更新成功一次；第二次（已非 PENDING）更新 0 行，重复执行不重复记效果
                int applied = st.executeUpdate("UPDATE command_plan_steps SET status='APPLIED',"
                        + " applied_by='alice', applied_at='" + Timestamp.from(now)
                        + "', updated_at='" + Timestamp.from(now)
                        + "' WHERE id = 1 AND status='PENDING'");
                assertThat(applied).isEqualTo(1);
                int reapply = st.executeUpdate("UPDATE command_plan_steps SET status='APPLIED',"
                        + " applied_by='alice' WHERE id = 1 AND status='PENDING'");
                assertThat(reapply).isZero();
                // APPLIED → COMPENSATED 成功；对已 COMPENSATED 行再补偿 0 行
                int compensated = st.executeUpdate("UPDATE command_plan_steps SET status='COMPENSATED',"
                        + " compensated_by='bob', compensated_at='" + Timestamp.from(now)
                        + "', updated_at='" + Timestamp.from(now)
                        + "' WHERE id = 1 AND status='APPLIED'");
                assertThat(compensated).isEqualTo(1);
                int recompensate = st.executeUpdate("UPDATE command_plan_steps SET status='COMPENSATED'"
                        + " WHERE id = 1 AND status='APPLIED'");
                assertThat(recompensate).isZero();

                // 补偿尝试证据/诊断可只追加两条（一次失败一次成功）
                st.execute("INSERT INTO command_plan_compensations (plan_id, step_id, step_index,"
                        + " attempt_no, status, evidence, failure_diagnostic, actor, request_id,"
                        + " created_at) VALUES (1,1,1,1,'FAILED',NULL,'下游超时','alice','REQ-1','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO command_plan_compensations (plan_id, step_id, step_index,"
                        + " attempt_no, status, evidence, failure_diagnostic, actor, request_id,"
                        + " created_at) VALUES (1,1,1,2,'SUCCEEDED','已回滚',NULL,'alice','REQ-2','"
                        + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM command_plan_compensations"
                        + " WHERE step_id = 1")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }
}
