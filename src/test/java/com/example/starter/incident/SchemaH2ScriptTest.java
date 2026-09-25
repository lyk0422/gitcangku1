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
    void h2Schema_agencyTables_enforceConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_agency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES"
                        + " ('IK-A','S1','s','r','COMMANDING','alice','" + Timestamp.from(now) + "',"
                        + "'" + Timestamp.from(now) + "')");

                // 任务优先级默认值 NORMAL，可显式置 HIGH
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code, title,"
                        + " status, created_by, created_at, updated_at) VALUES (1,'T-1','G','t',"
                        + "'OPEN','alice','" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT priority FROM incident_tasks WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("priority")).isEqualTo("NORMAL");
                }

                // 机构配置：(incident_id, version) 唯一
                st.execute("INSERT INTO incident_agency_configs (incident_id, version, agency_codes,"
                        + " status, created_by, created_at) VALUES (1,1,'FIRE,POLICE','ACTIVE',"
                        + "'alice','" + Timestamp.from(now) + "')");
                boolean duplicateVersionRejected = false;
                try {
                    st.execute("INSERT INTO incident_agency_configs (incident_id, version,"
                            + " agency_codes, status, created_by, created_at) VALUES (1,1,'MEDIC',"
                            + "'REPLACED','alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateVersionRejected = true;
                }
                assertThat(duplicateVersionRejected).isTrue();

                // 回执：(incident_id, config_version, agency_code) 唯一，同机构同版本仅一条终态
                st.execute("INSERT INTO incident_agency_receipts (incident_id, config_version,"
                        + " agency_code, receipt_type, reason, ack_key, created_at) VALUES"
                        + " (1,1,'FIRE','CONFIRM',NULL,'ACK-1','" + Timestamp.from(now) + "')");
                boolean duplicateReceiptRejected = false;
                try {
                    st.execute("INSERT INTO incident_agency_receipts (incident_id, config_version,"
                            + " agency_code, receipt_type, reason, ack_key, created_at) VALUES"
                            + " (1,1,'FIRE','REJECT','r','ACK-2','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateReceiptRejected = true;
                }
                assertThat(duplicateReceiptRejected).isTrue();
                // 同机构不同版本可各有一条回执（旧回执仅归属旧版本）
                st.execute("INSERT INTO incident_agency_receipts (incident_id, config_version,"
                        + " agency_code, receipt_type, reason, ack_key, created_at) VALUES"
                        + " (1,2,'FIRE','CONFIRM',NULL,'ACK-3','" + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) AS c FROM incident_agency_receipts")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("c")).isEqualTo(2);
                }

                // 事件阻断标记：blocked_from 可写可清空
                int blocked = st.executeUpdate("UPDATE incidents SET status='EXTERNAL_BLOCKED',"
                        + " blocked_from='COMMANDING' WHERE id = 1");
                assertThat(blocked).isEqualTo(1);
                int restored = st.executeUpdate("UPDATE incidents SET status='COMMANDING',"
                        + " blocked_from=NULL WHERE id = 1");
                assertThat(restored).isEqualTo(1);
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, blocked_from FROM incidents WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("COMMANDING");
                    assertThat(rs.getString("blocked_from")).isNull();
                }
            }
        }
    }
}
