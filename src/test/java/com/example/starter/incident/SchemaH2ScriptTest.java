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
                        + " status, work_grid, created_by, created_at, updated_at) VALUES"
                        + " (1,'T-1','G','t','OPEN','X1Y1','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateTaskRejected = false;
                try {
                    st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code,"
                            + " title, status, work_grid, created_by, created_at, updated_at) VALUES"
                            + " (1,'T-1','G','t2','OPEN','X1Y1','alice','" + Timestamp.from(now)
                            + "','" + Timestamp.from(now) + "')");
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

                // 疏散区域：(incident_id, zone_key) 唯一
                st.execute("INSERT INTO evacuation_zones (incident_id, zone_key, version, risk_level,"
                        + " grids, grid_count, effective_from, effective_to, status, registered_by,"
                        + " fingerprint, ended_at, created_at, updated_at) VALUES"
                        + " (1,'Z-1',1,'HIGH','[\"X1\"]',1,'" + Timestamp.from(now) + "','"
                        + Timestamp.from(now.plusSeconds(60)) + "','REGISTERED','alice','FP-1',"
                        + "NULL,'" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                boolean duplicateZoneKeyRejected = false;
                try {
                    st.execute("INSERT INTO evacuation_zones (incident_id, zone_key, version,"
                            + " risk_level, grids, grid_count, effective_from, effective_to, status,"
                            + " registered_by, fingerprint, ended_at, created_at, updated_at) VALUES"
                            + " (1,'Z-1',2,'LOW','[\"X2\"]',1,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now.plusSeconds(60)) + "','REGISTERED','alice','FP-2',"
                            + "NULL,'" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateZoneKeyRejected = true;
                }
                assertThat(duplicateZoneKeyRejected).isTrue();

                // 疏散区域：(incident_id, fingerprint) 唯一
                boolean duplicateFpRejected = false;
                try {
                    st.execute("INSERT INTO evacuation_zones (incident_id, zone_key, version,"
                            + " risk_level, grids, grid_count, effective_from, effective_to, status,"
                            + " registered_by, fingerprint, ended_at, created_at, updated_at) VALUES"
                            + " (1,'Z-2',2,'LOW','[\"X2\"]',1,'" + Timestamp.from(now) + "','"
                            + Timestamp.from(now.plusSeconds(60)) + "','REGISTERED','alice','FP-1',"
                            + "NULL,'" + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateFpRejected = true;
                }
                assertThat(duplicateFpRejected).isTrue();

                // 豁免：(zone_id, exempt_task_key) 唯一；租约：task_id 唯一
                st.execute("INSERT INTO evacuation_exemptions (incident_id, zone_id, version,"
                        + " exempt_task_key, granted_by, command_key, created_at) VALUES"
                        + " (1,1,1,'T-1','alice','CK-1','" + Timestamp.from(now) + "')");
                boolean duplicateExemptionRejected = false;
                try {
                    st.execute("INSERT INTO evacuation_exemptions (incident_id, zone_id, version,"
                            + " exempt_task_key, granted_by, command_key, created_at) VALUES"
                            + " (1,1,1,'T-1','alice','CK-2','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateExemptionRejected = true;
                }
                assertThat(duplicateExemptionRejected).isTrue();

                st.execute("INSERT INTO task_dispatch_leases (task_id, dispatched_by, command_key,"
                        + " dispatched_at, consumed_at) VALUES (1,'alice','CK-1','"
                        + Timestamp.from(now) + "',NULL)");
                boolean duplicateLeaseRejected = false;
                try {
                    st.execute("INSERT INTO task_dispatch_leases (task_id, dispatched_by,"
                            + " command_key, dispatched_at, consumed_at) VALUES (1,'alice','CK-2','"
                            + Timestamp.from(now) + "',NULL)");
                } catch (Exception e) {
                    duplicateLeaseRejected = true;
                }
                assertThat(duplicateLeaseRejected).isTrue();

                // 区域条件结束：仅 REGISTERED 可置 ENDED，重复结束 0 行
                int ended = st.executeUpdate("UPDATE evacuation_zones SET status='ENDED'"
                        + " WHERE id = 1 AND status = 'REGISTERED'");
                assertThat(ended).isEqualTo(1);
                int endedAgain = st.executeUpdate("UPDATE evacuation_zones SET status='ENDED'"
                        + " WHERE id = 1 AND status = 'REGISTERED'");
                assertThat(endedAgain).isZero();
            }
        }
    }
}
