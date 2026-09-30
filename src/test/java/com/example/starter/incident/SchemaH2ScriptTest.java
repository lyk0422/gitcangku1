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
     * 互助征调相关表约束与条件更新：库存可用量非负、租约归还量不超过征调量、
     * requestId 全局唯一；扣减/累计的条件 UPDATE 在库存不足或超量时影响 0 行。
     */
    @Test
    void h2Schema_enforcesMutualAidConstraints() throws Exception {
        String url = "jdbc:h2:mem:schema_h2_aid;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0";
        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("schema-h2.sql"));

            Instant now = Instant.parse("2026-09-22T00:00:00Z");
            Instant expiry = Instant.parse("2026-09-23T00:00:00Z");
            try (Statement st = con.createStatement()) {
                st.execute("INSERT INTO incident_resource_inventories (incident_id, resource_code,"
                        + " available_quantity, created_at, updated_at) VALUES (1,'R',5,'"
                        + Timestamp.from(now) + "','" + Timestamp.from(now) + "')");

                // 可用量不能为负：直接更新为 -1 被 CHECK 拒绝
                boolean negativeInventoryRejected = false;
                try {
                    st.execute("UPDATE incident_resource_inventories SET available_quantity = -1"
                            + " WHERE incident_id = 1 AND resource_code = 'R'");
                } catch (Exception e) {
                    negativeInventoryRejected = true;
                }
                assertThat(negativeInventoryRejected).isTrue();

                // 条件扣减：库存充足扣 3 影响 1 行；剩余 2 再扣 3 影响 0 行
                int deducted = st.executeUpdate("UPDATE incident_resource_inventories"
                        + " SET available_quantity = available_quantity - 3"
                        + " WHERE incident_id = 1 AND resource_code = 'R' AND available_quantity >= 3");
                assertThat(deducted).isEqualTo(1);
                int shortDeducted = st.executeUpdate("UPDATE incident_resource_inventories"
                        + " SET available_quantity = available_quantity - 3"
                        + " WHERE incident_id = 1 AND resource_code = 'R' AND available_quantity >= 3");
                assertThat(shortDeducted).isZero();

                st.execute("INSERT INTO resource_aid_leases (request_id, source_incident_id,"
                        + " target_incident_id, resource_code, quantity, returned_quantity, exclusive,"
                        + " status, expires_at, created_by, created_at, closed_at) VALUES"
                        + " ('REQ-1',1,2,'R',3,0,0,'ACTIVE','" + Timestamp.from(expiry)
                        + "','alice','" + Timestamp.from(now) + "',NULL)");

                // requestId 全局唯一
                boolean duplicateRequestRejected = false;
                try {
                    st.execute("INSERT INTO resource_aid_leases (request_id, source_incident_id,"
                            + " target_incident_id, resource_code, quantity, returned_quantity,"
                            + " exclusive, status, expires_at, created_by, created_at, closed_at)"
                            + " VALUES ('REQ-1',2,1,'S',1,0,0,'ACTIVE','" + Timestamp.from(expiry)
                            + "','bob','" + Timestamp.from(now) + "',NULL)");
                } catch (Exception e) {
                    duplicateRequestRejected = true;
                }
                assertThat(duplicateRequestRejected).isTrue();

                // 累计归还：还 2（<=3）影响 1 行；再还 2（累计 4 > 3）影响 0 行
                int firstReturn = st.executeUpdate("UPDATE resource_aid_leases"
                        + " SET returned_quantity = returned_quantity + 2"
                        + " WHERE id = 1 AND returned_quantity + 2 <= quantity");
                assertThat(firstReturn).isEqualTo(1);
                int overReturn = st.executeUpdate("UPDATE resource_aid_leases"
                        + " SET returned_quantity = returned_quantity + 2"
                        + " WHERE id = 1 AND returned_quantity + 2 <= quantity");
                assertThat(overReturn).isZero();

                // 直接把已还写成超过征调量被 CHECK 拒绝
                boolean returnedExceedsQuantityRejected = false;
                try {
                    st.execute("UPDATE resource_aid_leases SET returned_quantity = 4 WHERE id = 1");
                } catch (Exception e) {
                    returnedExceedsQuantityRejected = true;
                }
                assertThat(returnedExceedsQuantityRejected).isTrue();

                // 非正征调量被 CHECK 拒绝
                boolean nonPositiveQuantityRejected = false;
                try {
                    st.execute("INSERT INTO resource_aid_leases (request_id, source_incident_id,"
                            + " target_incident_id, resource_code, quantity, returned_quantity,"
                            + " exclusive, status, expires_at, created_by, created_at, closed_at)"
                            + " VALUES ('REQ-2',1,2,'R',0,0,0,'ACTIVE','" + Timestamp.from(expiry)
                            + "','alice','" + Timestamp.from(now) + "',NULL)");
                } catch (Exception e) {
                    nonPositiveQuantityRejected = true;
                }
                assertThat(nonPositiveQuantityRejected).isTrue();
            }
        }
    }
}
