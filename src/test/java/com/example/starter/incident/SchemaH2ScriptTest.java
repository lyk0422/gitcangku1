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

                // 资源凭据：(resource_key, version) 唯一
                st.execute("INSERT INTO resource_credentials (resource_key, version,"
                        + " credential_type, region, status, expires_at, revoked_at, created_at,"
                        + " updated_at) VALUES ('R','v1','AWS_AK','A','ACTIVE','"
                        + Timestamp.from(now.plusSeconds(3600)) + "',NULL,'" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                boolean duplicateCredentialRejected = false;
                try {
                    st.execute("INSERT INTO resource_credentials (resource_key, version,"
                            + " credential_type, region, status, expires_at, revoked_at, created_at,"
                            + " updated_at) VALUES ('R','v1','AWS_AK','B','ACTIVE','"
                            + Timestamp.from(now.plusSeconds(3600)) + "',NULL,'"
                            + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateCredentialRejected = true;
                }
                assertThat(duplicateCredentialRejected).isTrue();

                // 派发记录：request_id 全局唯一、(task_id, seq) 唯一；
                // 条件更新只作用于 DISPATCHED（撤销/执行竞争按已提交状态转移）
                st.execute("INSERT INTO task_dispatches (task_id, seq, request_id, resource_key,"
                        + " credential_version, credential_type, region, expires_at, status,"
                        + " block_reason, blocked_at, dispatched_at, dispatched_by, started_at)"
                        + " VALUES (1,1,'REQ-1','R','v1','AWS_AK','A','"
                        + Timestamp.from(now.plusSeconds(3600)) + "','DISPATCHED',NULL,NULL,'"
                        + Timestamp.from(now) + "','alice',NULL)");
                boolean duplicateRequestRejected = false;
                try {
                    st.execute("INSERT INTO task_dispatches (task_id, seq, request_id,"
                            + " resource_key, credential_version, credential_type, region,"
                            + " expires_at, status, block_reason, blocked_at, dispatched_at,"
                            + " dispatched_by, started_at) VALUES (1,2,'REQ-1','R','v2','AWS_AK',"
                            + "'A','" + Timestamp.from(now.plusSeconds(3600))
                            + "','DISPATCHED',NULL,NULL,'" + Timestamp.from(now)
                            + "','alice',NULL)");
                } catch (Exception e) {
                    duplicateRequestRejected = true;
                }
                assertThat(duplicateRequestRejected).isTrue();
                boolean duplicateSeqRejected = false;
                try {
                    st.execute("INSERT INTO task_dispatches (task_id, seq, request_id,"
                            + " resource_key, credential_version, credential_type, region,"
                            + " expires_at, status, block_reason, blocked_at, dispatched_at,"
                            + " dispatched_by, started_at) VALUES (1,1,'REQ-2','R','v1','AWS_AK',"
                            + "'A','" + Timestamp.from(now.plusSeconds(3600))
                            + "','DISPATCHED',NULL,NULL,'" + Timestamp.from(now)
                            + "','alice',NULL)");
                } catch (Exception e) {
                    duplicateSeqRejected = true;
                }
                assertThat(duplicateSeqRejected).isTrue();

                // 阻断条件更新命中 DISPATCHED 行；重复阻断更新 0 行
                int blocked = st.executeUpdate("UPDATE task_dispatches SET status='BLOCKED',"
                        + " block_reason='CREDENTIAL_EXPIRED', blocked_at='" + Timestamp.from(now)
                        + "' WHERE id = 1 AND status = 'DISPATCHED'");
                assertThat(blocked).isEqualTo(1);
                int blockedAgain = st.executeUpdate("UPDATE task_dispatches SET status='BLOCKED'"
                        + " WHERE id = 1 AND status = 'DISPATCHED'");
                assertThat(blockedAgain).isZero();
                int lateExecute = st.executeUpdate("UPDATE task_dispatches SET status='EXECUTING'"
                        + " WHERE id = 1 AND status = 'DISPATCHED'");
                assertThat(lateExecute).isZero();

                // 任务阻断字段与当前派发指针落库后可查
                st.executeUpdate("UPDATE incident_tasks SET status='BLOCKED',"
                        + " current_dispatch_id=1, block_reason='CREDENTIAL_EXPIRED', blocked_at='"
                        + Timestamp.from(now) + "' WHERE id = 1 AND status = 'OPEN'");
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, current_dispatch_id, block_reason FROM incident_tasks"
                                + " WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("BLOCKED");
                    assertThat(rs.getLong("current_dispatch_id")).isEqualTo(1L);
                    assertThat(rs.getString("block_reason")).isEqualTo("CREDENTIAL_EXPIRED");
                }
            }
        }
    }
}
