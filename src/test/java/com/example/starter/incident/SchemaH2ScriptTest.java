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

                // 共享资源：resource_key 唯一
                st.execute("INSERT INTO resources (resource_key, version, created_by, created_at,"
                        + " updated_at) VALUES ('RES-1',1,'alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateResourceRejected = false;
                try {
                    st.execute("INSERT INTO resources (resource_key, version, created_by,"
                            + " created_at, updated_at) VALUES ('RES-1',1,'alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateResourceRejected = true;
                }
                assertThat(duplicateResourceRejected).isTrue();

                // 资源资质：(resource_id, credential_code) 唯一
                st.execute("INSERT INTO resource_credentials (resource_id, credential_code,"
                        + " valid_from, valid_until, revoked, revoked_at, created_at) VALUES"
                        + " (1,'FIRE-A','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now.plusSeconds(3600)) + "',0,NULL,'"
                        + Timestamp.from(now) + "')");
                boolean duplicateCredentialRejected = false;
                try {
                    st.execute("INSERT INTO resource_credentials (resource_id, credential_code,"
                            + " valid_from, valid_until, revoked, revoked_at, created_at) VALUES"
                            + " (1,'FIRE-A','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now.plusSeconds(3600)) + "',0,NULL,'"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateCredentialRejected = true;
                }
                assertThat(duplicateCredentialRejected).isTrue();

                // 高危任务必需资质：(task_id, credential_code) 唯一
                st.execute("INSERT INTO task_required_credentials (task_id, credential_code,"
                        + " created_at) VALUES (1,'FIRE-A','" + Timestamp.from(now) + "')");
                boolean duplicateRequiredRejected = false;
                try {
                    st.execute("INSERT INTO task_required_credentials (task_id, credential_code,"
                            + " created_at) VALUES (1,'FIRE-A','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateRequiredRejected = true;
                }
                assertThat(duplicateRequiredRejected).isTrue();

                // 资源租约：(lease_key, task_id) 唯一；批量下同 leaseKey 可覆盖多任务
                st.execute("INSERT INTO resource_leases (lease_key, resource_id, resource_version,"
                        + " task_id, credential_codes, lease_start, lease_end, status,"
                        + " replaced_by, operator, created_at, updated_at) VALUES ('LK-1',1,1,1,"
                        + "'FIRE-A','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now.plusSeconds(3600)) + "','ACTIVE',NULL,'alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean duplicateLeaseRejected = false;
                try {
                    st.execute("INSERT INTO resource_leases (lease_key, resource_id,"
                            + " resource_version, task_id, credential_codes, lease_start,"
                            + " lease_end, status, replaced_by, operator, created_at, updated_at)"
                            + " VALUES ('LK-1',1,1,1,'FIRE-A','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now.plusSeconds(3600)) + "','ACTIVE',NULL,'alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateLeaseRejected = true;
                }
                assertThat(duplicateLeaseRejected).isTrue();

                // 资质风险记录：(lease_id, credential_code) 唯一，支撑只插入语义
                st.execute("INSERT INTO credential_risk_records (lease_id, task_id, resource_id,"
                        + " credential_code, revoked_at, detected_at) VALUES (1,1,1,'FIRE-A','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean duplicateRiskRejected = false;
                try {
                    st.execute("INSERT INTO credential_risk_records (lease_id, task_id,"
                            + " resource_id, credential_code, revoked_at, detected_at) VALUES"
                            + " (1,1,1,'FIRE-A','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateRiskRejected = true;
                }
                assertThat(duplicateRiskRejected).isTrue();

                // 任务风险状态流转的条件更新：仅 OPEN/IN_PROGRESS 可被标记为 CREDENTIAL_RISK
                int riskMarked = st.executeUpdate("UPDATE incident_tasks SET"
                        + " pre_risk_status = status, status = 'CREDENTIAL_RISK'"
                        + " WHERE id = 1 AND status IN ('OPEN','IN_PROGRESS')");
                assertThat(riskMarked).isEqualTo(1);
                int terminalSkipped = st.executeUpdate("UPDATE incident_tasks SET"
                        + " pre_risk_status = status, status = 'CREDENTIAL_RISK'"
                        + " WHERE id = 1 AND status IN ('OPEN','IN_PROGRESS')");
                assertThat(terminalSkipped).isZero();
                int restored = st.executeUpdate("UPDATE incident_tasks SET"
                        + " status = pre_risk_status, pre_risk_status = NULL"
                        + " WHERE id = 1 AND status = 'CREDENTIAL_RISK'");
                assertThat(restored).isEqualTo(1);
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, pre_risk_status FROM incident_tasks WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("OPEN");
                    assertThat(rs.getString("pre_risk_status")).isNull();
                }
            }
        }
    }
}
