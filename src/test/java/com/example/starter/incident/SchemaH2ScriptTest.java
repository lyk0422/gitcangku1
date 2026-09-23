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

                // 方案：plan_key 唯一
                st.execute("INSERT INTO plans (plan_key, active_version_id, created_at) VALUES"
                        + " ('PLAN-1',NULL,'" + Timestamp.from(now) + "')");
                boolean duplicatePlanRejected = false;
                try {
                    st.execute("INSERT INTO plans (plan_key, active_version_id, created_at)"
                            + " VALUES ('PLAN-1',NULL,'" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicatePlanRejected = true;
                }
                assertThat(duplicatePlanRejected).isTrue();

                // 方案版本：(plan_id, version_no) 唯一
                st.execute("INSERT INTO plan_versions (plan_id, version_no, status,"
                        + " base_version_id, expected_version, created_by, created_at) VALUES"
                        + " (1,1,'PUBLISHED',NULL,1,'alice','" + Timestamp.from(now) + "')");
                boolean duplicateVersionRejected = false;
                try {
                    st.execute("INSERT INTO plan_versions (plan_id, version_no, status,"
                            + " base_version_id, expected_version, created_by, created_at) VALUES"
                            + " (1,1,'DRAFT',NULL,1,'alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateVersionRejected = true;
                }
                assertThat(duplicateVersionRejected).isTrue();

                // 方案任务：(version_id, task_id) 唯一；依赖边：(version_id, from, to) 唯一
                st.execute("INSERT INTO plan_tasks (version_id, task_id, incident_key, group_code,"
                        + " title, assignee, status, completed_by, completed_at) VALUES"
                        + " (1,'T-1','INC-1','G','t','alice','PENDING',NULL,NULL)");
                boolean duplicatePlanTaskRejected = false;
                try {
                    st.execute("INSERT INTO plan_tasks (version_id, task_id, incident_key,"
                            + " group_code, title, assignee, status, completed_by, completed_at)"
                            + " VALUES (1,'T-1','INC-1','G','t2','alice','PENDING',NULL,NULL)");
                } catch (Exception e) {
                    duplicatePlanTaskRejected = true;
                }
                assertThat(duplicatePlanTaskRejected).isTrue();
                st.execute("INSERT INTO plan_edges (version_id, from_task_id, to_task_id)"
                        + " VALUES (1,'T-1','T-2')");
                boolean duplicatePlanEdgeRejected = false;
                try {
                    st.execute("INSERT INTO plan_edges (version_id, from_task_id, to_task_id)"
                            + " VALUES (1,'T-1','T-2')");
                } catch (Exception e) {
                    duplicatePlanEdgeRejected = true;
                }
                assertThat(duplicatePlanEdgeRejected).isTrue();

                // 合并证据：merge_key 与 request_id 全局唯一
                st.execute("INSERT INTO plan_merges (plan_id, merge_key, request_id, request_hash,"
                        + " base_version_id, left_version_id, right_version_id, left_expected,"
                        + " right_expected, result_version_id, diff_json, resolutions_json,"
                        + " created_by, created_at) VALUES (1,'MG-1','REQ-1','h',1,1,1,1,1,1,"
                        + " '{}','[]','alice','" + Timestamp.from(now) + "')");
                boolean duplicateMergeKeyRejected = false;
                try {
                    st.execute("INSERT INTO plan_merges (plan_id, merge_key, request_id,"
                            + " request_hash, base_version_id, left_version_id, right_version_id,"
                            + " left_expected, right_expected, result_version_id, diff_json,"
                            + " resolutions_json, created_by, created_at) VALUES (1,'MG-1','REQ-2',"
                            + " 'h2',1,1,1,1,1,1,'{}','[]','alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateMergeKeyRejected = true;
                }
                assertThat(duplicateMergeKeyRejected).isTrue();
                boolean duplicateRequestIdRejected = false;
                try {
                    st.execute("INSERT INTO plan_merges (plan_id, merge_key, request_id,"
                            + " request_hash, base_version_id, left_version_id, right_version_id,"
                            + " left_expected, right_expected, result_version_id, diff_json,"
                            + " resolutions_json, created_by, created_at) VALUES (1,'MG-2','REQ-1',"
                            + " 'h3',1,1,1,1,1,1,'{}','[]','alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateRequestIdRejected = true;
                }
                assertThat(duplicateRequestIdRejected).isTrue();
            }
        }
    }
}
