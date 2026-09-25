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
                        + " status, required_credentials, created_by, created_at, updated_at)"
                        + " VALUES (1,'T-1','G','t','OPEN','C1,C2','alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean duplicateTaskRejected = false;
                try {
                    st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code,"
                            + " title, status, required_credentials, created_by, created_at,"
                            + " updated_at) VALUES (1,'T-1','G','t2','OPEN','','alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateTaskRejected = true;
                }
                assertThat(duplicateTaskRejected).isTrue();

                // 资源资质：(resource_id, credential_code) 唯一
                st.execute("INSERT INTO resource_credentials (resource_id, credential_code,"
                        + " valid_from, valid_until, status, version, created_at, updated_at)"
                        + " VALUES ('R-1','C1',NULL,'" + Timestamp.from(now.plusSeconds(3600))
                        + "','ACTIVE',1,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");
                boolean duplicateCredentialRejected = false;
                try {
                    st.execute("INSERT INTO resource_credentials (resource_id, credential_code,"
                            + " valid_from, valid_until, status, version, created_at, updated_at)"
                            + " VALUES ('R-1','C1',NULL,'" + Timestamp.from(now.plusSeconds(3600))
                            + "','ACTIVE',1,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateCredentialRejected = true;
                }
                assertThat(duplicateCredentialRejected).isTrue();

                // 资源租约：每任务至多一条当前租约（task_id,current_flag=1）唯一
                st.execute("INSERT INTO resource_leases (task_id, resource_id, resource_version,"
                        + " lease_start, lease_end, required_credentials, status, current_flag,"
                        + " created_by, created_at) VALUES (1,'R-1',1,'" + Timestamp.from(now)
                        + "','" + Timestamp.from(now.plusSeconds(3600)) + "','C1','ACTIVE',1,"
                        + "'alice','" + Timestamp.from(now) + "')");
                boolean secondCurrentLeaseRejected = false;
                try {
                    st.execute("INSERT INTO resource_leases (task_id, resource_id,"
                            + " resource_version, lease_start, lease_end, required_credentials,"
                            + " status, current_flag, created_by, created_at) VALUES"
                            + " (1,'R-2',1,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now.plusSeconds(3600)) + "','C1','ACTIVE',1,"
                            + "'alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    secondCurrentLeaseRejected = true;
                }
                assertThat(secondCurrentLeaseRejected).isTrue();
                // 历史租约 current_flag=NULL 不与唯一约束冲突
                st.executeUpdate("UPDATE resource_leases SET current_flag = NULL WHERE task_id = 1");
                st.execute("INSERT INTO resource_leases (task_id, resource_id, resource_version,"
                        + " lease_start, lease_end, required_credentials, status, current_flag,"
                        + " created_by, created_at) VALUES (1,'R-2',1,'" + Timestamp.from(now)
                        + "','" + Timestamp.from(now.plusSeconds(3600)) + "','C1','ACTIVE',1,"
                        + "'alice','" + Timestamp.from(now) + "')");

                // 资质风险记录可正常追加
                st.execute("INSERT INTO credential_risks (lease_id, task_id, incident_id,"
                        + " resource_id, credential_code, reason, triggered_by, triggered_at,"
                        + " created_at) VALUES (2,1,1,'R-1','C1','revoked','alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");

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
}
