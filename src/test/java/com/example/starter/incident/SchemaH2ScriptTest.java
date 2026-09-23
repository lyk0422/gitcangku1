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

                // 图版本元数据：单行 id=1，重复插入被主键拒绝
                st.execute("INSERT INTO dependency_graph_meta (id, graph_version, updated_at)"
                        + " VALUES (1,1,'" + Timestamp.from(now) + "')");
                boolean duplicateMetaRejected = false;
                try {
                    st.execute("INSERT INTO dependency_graph_meta (id, graph_version, updated_at)"
                            + " VALUES (1,2,'" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateMetaRejected = true;
                }
                assertThat(duplicateMetaRejected).isTrue();

                // 权威依赖边：(from_incident_id, to_incident_id) 结构化唯一
                st.execute("INSERT INTO incident_dependency_edges (from_incident_id, to_incident_id,"
                        + " source, ref_id, created_at) VALUES (1,1,'PROPOSAL',NULL,'"
                        + Timestamp.from(now) + "')");
                boolean duplicateEdgeRejected = false;
                try {
                    st.execute("INSERT INTO incident_dependency_edges (from_incident_id, to_incident_id,"
                            + " source, ref_id, created_at) VALUES (1,1,'TASK',1,'"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateEdgeRejected = true;
                }
                assertThat(duplicateEdgeRejected).isTrue();

                // 提案：proposal_key 唯一；activated_graph_version 唯一（多个 NULL 允许）
                st.execute("INSERT INTO dependency_change_proposals (proposal_key, expected_graph_version,"
                        + " business_note, safety_reviewer, created_by, changes_json, status,"
                        + " created_at) VALUES ('P-1',1,'n','sec','alice','[]','PENDING','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO dependency_change_proposals (proposal_key, expected_graph_version,"
                        + " business_note, safety_reviewer, created_by, changes_json, status,"
                        + " created_at) VALUES ('P-2',1,'n','sec','alice','[]','PENDING','"
                        + Timestamp.from(now) + "')");
                boolean duplicateProposalRejected = false;
                try {
                    st.execute("INSERT INTO dependency_change_proposals (proposal_key,"
                            + " expected_graph_version, business_note, safety_reviewer, created_by,"
                            + " changes_json, status, created_at) VALUES ('P-1',1,'n','sec','alice',"
                            + "'[]','PENDING','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateProposalRejected = true;
                }
                assertThat(duplicateProposalRejected).isTrue();
                // 激活版本号唯一：同一版本不能被两个提案占用
                int firstActivated = st.executeUpdate("UPDATE dependency_change_proposals"
                        + " SET status='ACTIVATED', activated_graph_version=2 WHERE id=1");
                assertThat(firstActivated).isEqualTo(1);
                boolean duplicateVersionRejected = false;
                try {
                    st.executeUpdate("UPDATE dependency_change_proposals"
                            + " SET status='ACTIVATED', activated_graph_version=2 WHERE id=2");
                } catch (Exception e) {
                    duplicateVersionRejected = true;
                }
                assertThat(duplicateVersionRejected).isTrue();

                // 名册可冻结同一人员的多个席位（不冲突）
                st.execute("INSERT INTO proposal_roster_entries (proposal_id, incident_id, person_id,"
                        + " role, created_at) VALUES (1,1,'alice','COMMANDER','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO proposal_roster_entries (proposal_id, incident_id, person_id,"
                        + " role, created_at) VALUES (1,NULL,'alice','SAFETY_REVIEWER','"
                        + Timestamp.from(now) + "')");

                // 票决：(proposal_id, person_id) 唯一，兼任多席位也只允许一票
                st.execute("INSERT INTO proposal_votes (proposal_id, person_id, choice, voted_at,"
                        + " created_at) VALUES (1,'alice','YES','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                boolean duplicateVoteRejected = false;
                try {
                    st.execute("INSERT INTO proposal_votes (proposal_id, person_id, choice, voted_at,"
                            + " created_at) VALUES (1,'alice','NO','" + Timestamp.from(now) + "','"
                            + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateVoteRejected = true;
                }
                assertThat(duplicateVoteRejected).isTrue();
            }
        }
    }
}
