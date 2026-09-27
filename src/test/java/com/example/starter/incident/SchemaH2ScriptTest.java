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

                // 封存包：(incident_id, version) 唯一，版本不可重复
                st.execute("INSERT INTO incident_log_seals (incident_id, version, cutoff_at,"
                        + " sealed_at, created_by, record_count, content_hash) VALUES"
                        + " (1,1,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "','alice',1,'h1')");
                boolean duplicateSealVersionRejected = false;
                try {
                    st.execute("INSERT INTO incident_log_seals (incident_id, version, cutoff_at,"
                            + " sealed_at, created_by, record_count, content_hash) VALUES"
                            + " (1,1,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                            + "','alice',1,'h2')");
                } catch (Exception e) {
                    duplicateSealVersionRejected = true;
                }
                assertThat(duplicateSealVersionRejected).isTrue();
                // 不同事件可各自从版本 1 开始
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter,"
                        + " status, created_at, updated_at) VALUES ('IK-2','S1','s','r',"
                        + "'REPORTED','" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_log_seals (incident_id, version, cutoff_at,"
                        + " sealed_at, created_by, record_count, content_hash) VALUES"
                        + " (2,1,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "','alice',0,'h3')");

                // 包条目：(seal_id, record_type, record_key) 唯一；序号与依赖边端点可空可非空
                st.execute("INSERT INTO incident_log_seal_items (seal_id, seq_no, record_type,"
                        + " record_key, occurred_at, record_version, from_task_key,"
                        + " to_incident_key, payload) VALUES (1,1,'ACTION','A1','"
                        + Timestamp.from(now) + "',1,NULL,NULL,'{}')");
                st.execute("INSERT INTO incident_log_seal_items (seal_id, seq_no, record_type,"
                        + " record_key, occurred_at, record_version, from_task_key,"
                        + " to_incident_key, payload) VALUES (1,2,'DEPENDENCY','dep:T1:IK-2','"
                        + Timestamp.from(now) + "',1,'T1','IK-2','{}')");
                boolean duplicateItemRejected = false;
                try {
                    st.execute("INSERT INTO incident_log_seal_items (seal_id, seq_no, record_type,"
                            + " record_key, occurred_at, record_version, from_task_key,"
                            + " to_incident_key, payload) VALUES (1,3,'ACTION','A1','"
                            + Timestamp.from(now) + "',1,NULL,NULL,'{}')");
                } catch (Exception e) {
                    duplicateItemRejected = true;
                }
                assertThat(duplicateItemRejected).isTrue();

                // 更正附录：只追加，允许 old_value 为 NULL（保留 null 语义），new_value 非空
                st.execute("INSERT INTO incident_log_corrections (incident_id, package_version,"
                        + " record_type, record_key, field_name, old_value, new_value, reason,"
                        + " operator, created_at) VALUES (1,1,'ACTION','A1','note',NULL,'新值',"
                        + "'录入错误','alice','" + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT old_value, new_value FROM incident_log_corrections"
                                + " WHERE incident_id = 1 AND package_version = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("old_value")).isNull();
                    assertThat(rs.getString("new_value")).isEqualTo("新值");
                }
            }
        }
    }
}
