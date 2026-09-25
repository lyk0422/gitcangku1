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
    void h2Schema_mergeTableAndVersionColumns() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            try (Statement st = con.createStatement()) {
                // version 默认 0、merged_into_id 默认空
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES"
                        + " ('IK-S','S1','s','r','COMMANDING','alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES"
                        + " ('IK-M','S1','s','r','COMMANDING','alice','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
                try (ResultSet rs = st.executeQuery(
                        "SELECT version, merged_into_id FROM incidents WHERE incident_key = 'IK-S'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("version")).isZero();
                    assertThat(rs.getObject("merged_into_id")).isNull();
                }

                // 合并记录落库与 merge_key 唯一约束
                st.execute("INSERT INTO incident_merges (merge_key, surviving_incident_id,"
                        + " merged_incident_id, actor, created_at) VALUES ('MRG-1',1,2,'alice','"
                        + Timestamp.from(now) + "')");
                boolean duplicateMergeRejected = false;
                try {
                    st.execute("INSERT INTO incident_merges (merge_key, surviving_incident_id,"
                            + " merged_incident_id, actor, created_at) VALUES ('MRG-1',1,2,"
                            + "'alice','" + Timestamp.from(now) + "')");
                } catch (Exception e) {
                    duplicateMergeRejected = true;
                }
                assertThat(duplicateMergeRejected).isTrue();

                // 版本加一与 MERGED 终态标记
                int bumped = st.executeUpdate("UPDATE incidents SET status = 'MERGED',"
                        + " merged_into_id = 1, deadline_at = NULL, version = version + 1"
                        + " WHERE id = 2");
                assertThat(bumped).isEqualTo(1);
                try (ResultSet rs = st.executeQuery(
                        "SELECT status, version, merged_into_id FROM incidents WHERE id = 2")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("MERGED");
                    assertThat(rs.getLong("version")).isEqualTo(1L);
                    assertThat(rs.getLong("merged_into_id")).isEqualTo(1L);
                }

                // 任务迁移：origin_incident_id 记录首次来源，COALESCE 不覆盖已有来源
                st.execute("INSERT INTO incident_tasks (incident_id, task_key, group_code, title,"
                        + " status, created_by, created_at, updated_at) VALUES (2,'T-1','G','t',"
                        + "'OPEN','alice','" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "')");
                st.executeUpdate("UPDATE incident_tasks SET incident_id = 1,"
                        + " origin_incident_id = COALESCE(origin_incident_id, 2)"
                        + " WHERE incident_id = 2");
                st.executeUpdate("UPDATE incident_tasks SET incident_id = 2,"
                        + " origin_incident_id = COALESCE(origin_incident_id, 1)"
                        + " WHERE incident_id = 1");
                try (ResultSet rs = st.executeQuery(
                        "SELECT incident_id, origin_incident_id FROM incident_tasks"
                                + " WHERE task_key = 'T-1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("incident_id")).isEqualTo(2L);
                    assertThat(rs.getLong("origin_incident_id")).isEqualTo(2L);
                }
            }
        }
    }
}
