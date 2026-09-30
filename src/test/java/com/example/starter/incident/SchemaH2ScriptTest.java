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
                        + " resource_key, region, status, created_by, created_at, updated_at) VALUES"
                        + " (1,'T-1','G','t','RES-R','A','OPEN','alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                boolean duplicateTaskRejected = false;
                try {
                    st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code,"
                            + " title, resource_key, region, status, created_by, created_at,"
                            + " updated_at) VALUES (1,'T-1','G','t2','RES-R','A','OPEN','alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateTaskRejected = true;
                }
                assertThat(duplicateTaskRejected).isTrue();

                // 资源凭据：(resource_key, version) 唯一
                st.execute("INSERT INTO resource_credentials (resource_key, version, credential_type,"
                        + " region, expires_at, status, created_by, created_at, updated_at) VALUES"
                        + " ('RES-R',1,'TOKEN','A','" + Timestamp.from(now.plusSeconds(60))
                        + "','ACTIVE','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateCredentialRejected = false;
                try {
                    st.execute("INSERT INTO resource_credentials (resource_key, version,"
                            + " credential_type, region, expires_at, status, created_by, created_at,"
                            + " updated_at) VALUES ('RES-R',1,'TOKEN','A','"
                            + Timestamp.from(now.plusSeconds(60)) + "','ACTIVE','alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateCredentialRejected = true;
                }
                assertThat(duplicateCredentialRejected).isTrue();

                // 任务条件转移：OPEN→DISPATCHED 成功，重复 DISPATCHED→EXECUTING 之外的
                // DISPATCHED 二次派发更新 0 行
                int dispatched = st.executeUpdate("UPDATE incident_tasks SET status='DISPATCHED',"
                        + " credential_version=1, updated_at='" + Timestamp.from(now)
                        + "' WHERE id = 1 AND status IN ('OPEN','BLOCKED')");
                assertThat(dispatched).isEqualTo(1);
                int secondDispatch = st.executeUpdate("UPDATE incident_tasks SET status='DISPATCHED',"
                        + " updated_at='" + Timestamp.from(now) + "' WHERE id = 1"
                        + " AND status IN ('OPEN','BLOCKED')");
                assertThat(secondDispatch).isZero();
                // DISPATCHED→BLOCKED（撤销）与撤销条件更新只作用于 ACTIVE
                int blocked = st.executeUpdate("UPDATE incident_tasks SET status='BLOCKED',"
                        + " credential_version=1, block_reason='CREDENTIAL_REVOKED', updated_at='"
                        + Timestamp.from(now) + "' WHERE id = 1 AND status IN ('DISPATCHED','EXECUTING')");
                assertThat(blocked).isEqualTo(1);
                int revokeActive = st.executeUpdate(
                        "UPDATE resource_credentials SET status='REVOKED', revoked_at='"
                                + Timestamp.from(now) + "', updated_at='" + Timestamp.from(now)
                                + "' WHERE id = 1 AND status = 'ACTIVE'");
                assertThat(revokeActive).isEqualTo(1);
                int revokeAgain = st.executeUpdate(
                        "UPDATE resource_credentials SET status='REVOKED' WHERE id = 1"
                                + " AND status = 'ACTIVE'");
                assertThat(revokeAgain).isZero();

                // 派发记录：(task_id, request_id) 唯一
                st.execute("INSERT INTO task_dispatches (incident_id, task_id, request_id,"
                        + " resource_key, credential_id, credential_version, credential_type, region,"
                        + " credential_expires_at, status, dispatched_by, dispatched_at, created_at,"
                        + " updated_at) VALUES (1,1,'REQ-1','RES-R',1,1,'TOKEN','A','"
                        + Timestamp.from(now.plusSeconds(60)) + "','DISPATCHED','alice','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateDispatchRejected = false;
                try {
                    st.execute("INSERT INTO task_dispatches (incident_id, task_id, request_id,"
                            + " resource_key, credential_id, credential_version, credential_type,"
                            + " region, credential_expires_at, status, dispatched_by, dispatched_at,"
                            + " created_at, updated_at) VALUES (1,1,'REQ-1','RES-R',1,1,'TOKEN','A','"
                            + Timestamp.from(now.plusSeconds(60)) + "','DISPATCHED','alice','"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateDispatchRejected = true;
                }
                assertThat(duplicateDispatchRejected).isTrue();

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
