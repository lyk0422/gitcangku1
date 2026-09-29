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

                // 外部通报：每事件至多一份通报单
                st.execute("INSERT INTO incident_notifications (incident_id, escalation_id, created_at)"
                        + " VALUES (1,1,'" + Timestamp.from(now) + "')");
                boolean duplicateNotificationRejected = false;
                try {
                    st.execute("INSERT INTO incident_notifications (incident_id, escalation_id, created_at)"
                            + " VALUES (1,1,'" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateNotificationRejected = true;
                }
                assertThat(duplicateNotificationRejected).isTrue();

                // 通报机构：同通报机构标识唯一
                st.execute("INSERT INTO incident_notification_agencies (notification_id, incident_id,"
                        + " agency_code, critical, ack_deadline, created_at) VALUES (1,1,'甲',TRUE,'"
                        + Timestamp.from(now.plusSeconds(300)) + "','" + Timestamp.from(now) + "')");
                boolean duplicateAgencyRejected = false;
                try {
                    st.execute("INSERT INTO incident_notification_agencies (notification_id, incident_id,"
                            + " agency_code, critical, ack_deadline, created_at) VALUES (1,1,'甲',FALSE,'"
                            + Timestamp.from(now.plusSeconds(600)) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateAgencyRejected = true;
                }
                assertThat(duplicateAgencyRejected).isTrue();
                st.execute("INSERT INTO incident_notification_agencies (notification_id, incident_id,"
                        + " agency_code, critical, ack_deadline, created_at) VALUES (1,1,'乙',FALSE,'"
                        + Timestamp.from(now.plusSeconds(600)) + "','" + Timestamp.from(now) + "')");

                // 回执：同一机构（agency_id）唯一——只计首份有效回执
                st.execute("INSERT INTO incident_notification_receipts (agency_id, incident_id,"
                        + " agency_code, request_id, signer, content, received_at, created_at) VALUES"
                        + " (1,1,'甲','R-1','s','c','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateAgencyReceiptRejected = false;
                try {
                    st.execute("INSERT INTO incident_notification_receipts (agency_id, incident_id,"
                            + " agency_code, request_id, signer, content, received_at, created_at) VALUES"
                            + " (1,1,'甲','R-2','s2','c2','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateAgencyReceiptRejected = true;
                }
                assertThat(duplicateAgencyReceiptRejected).isTrue();

                // 回执：request_id 全局唯一——报文幂等
                boolean duplicateRequestRejected = false;
                try {
                    st.execute("INSERT INTO incident_notification_receipts (agency_id, incident_id,"
                            + " agency_code, request_id, signer, content, received_at, created_at) VALUES"
                            + " (2,1,'乙','R-1','s','c','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateRequestRejected = true;
                }
                assertThat(duplicateRequestRejected).isTrue();

                // 恰好到期（当前时刻 = 截止时刻）在 SQL 层即判定为逾期
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM"
                        + " incident_notification_agencies WHERE '" + Timestamp.from(now.plusSeconds(300))
                        + "' >= ack_deadline")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }
}
