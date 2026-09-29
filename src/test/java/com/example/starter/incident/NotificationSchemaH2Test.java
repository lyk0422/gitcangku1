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
 * 外部通报回执表的真实 H2（MODE=MySQL）约束验证：
 * 通报每事件/每升级唯一、通报内机构唯一、request_id 全局唯一，
 * 以及“仅首份回执写回机构行（received_at IS NULL 条件更新）”的 SQL 语义。
 * 使用独立命名内存库，连接关闭即释放，不依赖外部数据库。
 */
class NotificationSchemaH2Test {

    @Test
    void notificationTables_enforceUniquenessAndFirstAckUpdate() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_notification;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));
            Instant now = Instant.parse("2026-09-29T11:00:00Z");
            Instant deadline = Instant.parse("2026-09-29T12:00:00Z");
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at, deadline_at) VALUES"
                        + " ('IK-1','S1','s','r','COMMANDING','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "','" + Timestamp.from(now.plusSeconds(300)) + "')");
                st.execute("INSERT INTO incident_escalations (incident_id, deadline_at, triggered_at,"
                        + " triggered_commander, status, note, acknowledged_by, acknowledged_at,"
                        + " created_at, updated_at) VALUES (1,'" + Timestamp.from(now.plusSeconds(300))
                        + "','" + Timestamp.from(now) + "','alice','OPEN',NULL,NULL,NULL,'"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_notifications (incident_id, escalation_id,"
                        + " created_at, updated_at) VALUES (1,1,'" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");

                // 每事件唯一通报
                expectConstraintFailure(st, "INSERT INTO incident_notifications (incident_id,"
                        + " escalation_id, created_at, updated_at) VALUES (1,1,'" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");

                st.execute("INSERT INTO incident_notification_agencies (notification_id, agency_key,"
                        + " critical, ack_deadline, first_request_id, signer, received_at,"
                        + " created_at, updated_at) VALUES (1,'jia',TRUE,'" + Timestamp.from(deadline)
                        + "',NULL,NULL,NULL,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");
                // 通报内机构键唯一
                expectConstraintFailure(st, "INSERT INTO incident_notification_agencies"
                        + " (notification_id, agency_key, critical, ack_deadline, first_request_id,"
                        + " signer, received_at, created_at, updated_at) VALUES (1,'jia',TRUE,'"
                        + Timestamp.from(deadline) + "',NULL,NULL,NULL,'" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");

                Instant firstAt = Instant.parse("2026-09-29T11:30:00Z");
                st.execute("INSERT INTO incident_notification_acks (agency_notification_id,"
                        + " request_id, signer, content, received_at, created_at) VALUES"
                        + " (1,'REQ-1','zhang','first','" + Timestamp.from(firstAt) + "','"
                        + Timestamp.from(firstAt) + "')");
                // request_id 全局唯一
                expectConstraintFailure(st, "INSERT INTO incident_notification_acks"
                        + " (agency_notification_id, request_id, signer, content, received_at,"
                        + " created_at) VALUES (1,'REQ-1','li','again','"
                        + Timestamp.from(deadline) + "','" + Timestamp.from(deadline) + "')");

                // 首份回执条件更新：received_at IS NULL 时写回，更新 1 行
                int first = st.executeUpdate("UPDATE incident_notification_agencies"
                        + " SET first_request_id='REQ-1', signer='zhang', received_at='"
                        + Timestamp.from(firstAt) + "', updated_at='" + Timestamp.from(firstAt)
                        + "' WHERE id=1 AND received_at IS NULL");
                assertThat(first).isEqualTo(1);
                // 重复报文条件更新 0 行：不改写首份时间/签收人
                int second = st.executeUpdate("UPDATE incident_notification_agencies"
                        + " SET first_request_id='REQ-2', signer='li', received_at='"
                        + Timestamp.from(deadline) + "', updated_at='" + Timestamp.from(deadline)
                        + "' WHERE id=1 AND received_at IS NULL");
                assertThat(second).isZero();

                try (ResultSet rs = st.executeQuery("SELECT first_request_id, signer, received_at"
                        + " FROM incident_notification_agencies WHERE id=1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("first_request_id")).isEqualTo("REQ-1");
                    assertThat(rs.getString("signer")).isEqualTo("zhang");
                    assertThat(rs.getTimestamp("received_at").toInstant()).isEqualTo(firstAt);
                }
            }
        }
    }

    private static void expectConstraintFailure(Statement st, String sql) {
        boolean failed = false;
        try {
            st.execute(sql);
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).as("唯一约束应拒绝: %s", sql).isTrue();
    }
}
