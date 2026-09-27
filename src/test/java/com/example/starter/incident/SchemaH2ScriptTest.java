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

    /**
     * 验证提案、所需角色与投票表的关键约束：任务版本默认 1、提案键事件内唯一、
     * 所需角色集合唯一、每提案每角色至多一张 CAST 票（active_role 部分唯一，
     * STALE/WITHDRAWN 置 NULL 后允许同角色重新投票，多个 NULL 不冲突）、
     * 生效/到期条件更新只作用于 OPEN。
     */
    @Test
    void h2Schema_enforcesDecisionConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_decision;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            Instant expiry = now.plusSeconds(600);
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES"
                        + " ('IK-1','S1','s','r','COMMANDING','alice','" + Timestamp.from(now) + "',"
                        + "'" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, revision, group_code,"
                        + " title, status, created_by, created_at, updated_at) VALUES"
                        + " (1,'T-1',1,'G','t','OPEN','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                // revision 列默认值为 1
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code, title,"
                        + " status, created_by, created_at, updated_at) VALUES"
                        + " (1,'T-2','G','t2','OPEN','alice','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT revision FROM incident_tasks WHERE task_key = 'T-2'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                st.execute("INSERT INTO incident_decisions (incident_id, proposal_key, task_key,"
                        + " decision_type, expires_at, quorum, base_task_revision, status,"
                        + " created_by, created_at) VALUES (1,'P-1','T-1','TASK_COMPLETE','"
                        + Timestamp.from(expiry) + "',1,1,'OPEN','alice','" + Timestamp.from(now)
                        + "')");
                // (incident_id, proposal_key) 唯一
                boolean dupProposalRejected = false;
                try {
                    st.execute("INSERT INTO incident_decisions (incident_id, proposal_key, task_key,"
                            + " decision_type, expires_at, quorum, base_task_revision, status,"
                            + " created_by, created_at) VALUES (1,'P-1','T-1','TASK_CANCEL','"
                            + Timestamp.from(expiry) + "',1,1,'OPEN','alice','" + Timestamp.from(now)
                            + "')");
                } catch (Exception e) {
                    dupProposalRejected = true;
                }
                assertThat(dupProposalRejected).isTrue();

                st.execute("INSERT INTO decision_required_roles (proposal_id, role_code) VALUES"
                        + " (1,'OPS')");
                boolean dupRoleRejected = false;
                try {
                    st.execute("INSERT INTO decision_required_roles (proposal_id, role_code)"
                            + " VALUES (1,'OPS')");
                } catch (Exception e) {
                    dupRoleRejected = true;
                }
                assertThat(dupRoleRejected).isTrue();

                st.execute("INSERT INTO decision_votes (proposal_id, task_revision, voter_actor,"
                        + " role_code, choice, status, voted_at, active_role) VALUES"
                        + " (1,1,'u1','OPS','SUPPORT','CAST','" + Timestamp.from(now) + "','OPS')");
                // 同提案同角色第二张 CAST 票被部分唯一索引拒绝
                boolean dupCastRejected = false;
                try {
                    st.execute("INSERT INTO decision_votes (proposal_id, task_revision, voter_actor,"
                            + " role_code, choice, status, voted_at, active_role) VALUES"
                            + " (1,1,'u2','OPS','OPPOSE','CAST','" + Timestamp.from(now)
                            + "','OPS')");
                } catch (Exception e) {
                    dupCastRejected = true;
                }
                assertThat(dupCastRejected).isTrue();

                // 旧票失效与撤回后 active_role 置 NULL：多个 NULL 互不冲突，角色可重新投
                int staleRows = st.executeUpdate("UPDATE decision_votes SET status = 'STALE',"
                        + " stale_at = '" + Timestamp.from(now) + "', active_role = NULL"
                        + " WHERE id = 1");
                assertThat(staleRows).isEqualTo(1);
                st.execute("INSERT INTO decision_votes (proposal_id, task_revision, voter_actor,"
                        + " role_code, choice, status, voted_at, active_role) VALUES"
                        + " (1,2,'u1','OPS','SUPPORT','CAST','" + Timestamp.from(now) + "','OPS')");
                int withdrawRows = st.executeUpdate("UPDATE decision_votes SET status = 'WITHDRAWN',"
                        + " withdrawn_at = '" + Timestamp.from(now) + "', active_role = NULL"
                        + " WHERE proposal_id = 1 AND task_revision = 2");
                assertThat(withdrawRows).isEqualTo(1);
                st.execute("INSERT INTO decision_votes (proposal_id, task_revision, voter_actor,"
                        + " role_code, choice, status, voted_at, active_role) VALUES"
                        + " (1,2,'u1','OPS','SUPPORT','CAST','" + Timestamp.from(now) + "','OPS')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM decision_votes WHERE proposal_id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }

                // 生效条件更新只作用于 OPEN：先到期则生效更新 0 行
                int expired = st.executeUpdate("UPDATE incident_decisions SET status = 'EXPIRED',"
                        + " expired_at = '" + Timestamp.from(expiry) + "'"
                        + " WHERE id = 1 AND status = 'OPEN'");
                assertThat(expired).isEqualTo(1);
                int lateEffect = st.executeUpdate("UPDATE incident_decisions SET status = 'EFFECTIVE'"
                        + " WHERE id = 1 AND status = 'OPEN'");
                assertThat(lateEffect).isZero();
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, effective_task_revision, expired_at FROM incident_decisions"
                                + " WHERE id = 1")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("EXPIRED");
                    assertThat(rs.getObject("effective_task_revision")).isNull();
                    assertThat(rs.getTimestamp("expired_at").toInstant()).isEqualTo(expiry);
                }
            }
        }
    }
}
