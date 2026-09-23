package com.example.starter.blind;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧库结构迁移的真实 H2（MODE=MySQL）验证：
 * 先按 v1 结构建表并写入历史 PENDING/APPROVED 行，再执行当前 classpath:schema.sql，
 * 断言历史 PENDING 以 created_at+30 分钟回填有效期、原 APPROVED 不追溯设限，
 * 且旧 CHECK 约束被替换后历史 PENDING 到期可以归档为 EXPIRED；迁移脚本重复执行幂等。
 */
class LegacySchemaMigrationH2Test {

    /** v1 版本的揭盲申请表：无 expires_at/valid_minutes/终止字段，CHECK 仅允许 PENDING/APPROVED。 */
    private static final String LEGACY_DDL = """
            CREATE TABLE unblind_request (
                id                      VARCHAR(64)  NOT NULL,
                experiment_id           VARCHAR(64)  NOT NULL,
                participant_id          VARCHAR(64)  NOT NULL,
                allocation_id           BIGINT       NOT NULL,
                reason                  VARCHAR(500) NOT NULL,
                applicant_actor         VARCHAR(64)  NOT NULL,
                reviewer_actor          VARCHAR(64),
                status                  VARCHAR(16)  NOT NULL,
                treatment               VARCHAR(1),
                created_at              BIGINT       NOT NULL,
                reviewed_at             BIGINT,
                pending_allocation_id   BIGINT,
                CONSTRAINT pk_unblind_request PRIMARY KEY (id),
                CONSTRAINT uq_unblind_pending UNIQUE (pending_allocation_id),
                CONSTRAINT ck_unblind_status CHECK (status IN ('PENDING', 'APPROVED')),
                CONSTRAINT ck_unblind_treatment CHECK (treatment IS NULL OR treatment IN ('A', 'B'))
            );
            """;

    @Test
    void migratingLegacyTable_backfillsExpiry_andAllowsExpiredArchiving() throws Exception {
        String url = "jdbc:h2:mem:legacy_migration_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
            // 1) 构造 v1 旧库与历史数据
            try (Statement st = conn.createStatement()) {
                st.execute(LEGACY_DDL);
                st.execute("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, reviewer_actor, status, "
                        + "treatment, created_at, reviewed_at, pending_allocation_id) VALUES "
                        + "('UB-OLD-PENDING', 'E1', 'P1', 100, '历史待审', 'c1', NULL, "
                        + "'PENDING', NULL, 1700000000000, NULL, 100)");
                st.execute("INSERT INTO unblind_request (id, experiment_id, participant_id, "
                        + "allocation_id, reason, applicant_actor, reviewer_actor, status, "
                        + "treatment, created_at, reviewed_at, pending_allocation_id) VALUES "
                        + "('UB-OLD-APPROVED', 'E1', 'P2', 101, '历史批准', 'c1', 'r2', "
                        + "'APPROVED', 'A', 1699900000000, 1699900600000, NULL)");
            }

            // 2) 执行当前版本 schema.sql（CREATE IF NOT EXISTS + ALTER 迁移）
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));

            // 3) 历史 PENDING：created_at + 30 分钟有效期，默认时长 30
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT valid_minutes, expires_at, status, "
                         + "terminated_at, terminate_actor, treatment, pending_allocation_id "
                         + "FROM unblind_request WHERE id = 'UB-OLD-PENDING'")) {
                assertTrue(rs.next());
                assertEquals(30, rs.getInt("valid_minutes"));
                assertEquals(1_700_000_000_000L + 30L * 60_000L, rs.getLong("expires_at"));
                assertEquals("PENDING", rs.getString("status"));
                assertNull(rs.getObject("terminated_at"));
                assertNull(rs.getObject("terminate_actor"));
                assertNull(rs.getString("treatment"));
                assertEquals(100L, rs.getLong("pending_allocation_id"));
            }

            // 4) 原 APPROVED 结果不追溯设限：状态/盲底保留，仅回填时间列
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT valid_minutes, expires_at, status, "
                         + "treatment, reviewer_actor, pending_allocation_id "
                         + "FROM unblind_request WHERE id = 'UB-OLD-APPROVED'")) {
                assertTrue(rs.next());
                assertEquals(30, rs.getInt("valid_minutes"));
                assertEquals(1_699_900_000_000L + 30L * 60_000L, rs.getLong("expires_at"));
                assertEquals("APPROVED", rs.getString("status"));
                assertEquals("A", rs.getString("treatment"));
                assertEquals("r2", rs.getString("reviewer_actor"));
                assertNull(rs.getObject("pending_allocation_id"));
            }

            // 5) 旧 CHECK 已替换：历史 PENDING 到达 expiresAt 后可归档为 EXPIRED（v1 约束下会失败）
            long now = 1_700_000_000_000L + 30L * 60_000L;
            int archived;
            try (Statement st = conn.createStatement()) {
                archived = st.executeUpdate("UPDATE unblind_request SET status = 'EXPIRED', "
                        + "terminated_at = expires_at, pending_allocation_id = NULL "
                        + "WHERE id = 'UB-OLD-PENDING' AND status = 'PENDING' AND "
                        + now + " >= expires_at");
            }
            assertEquals(1, archived);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status, terminated_at, terminate_actor, "
                         + "terminate_reason, treatment, pending_allocation_id "
                         + "FROM unblind_request WHERE id = 'UB-OLD-PENDING'")) {
                assertTrue(rs.next());
                assertEquals("EXPIRED", rs.getString("status"));
                assertEquals(1_700_000_000_000L + 30L * 60_000L, rs.getLong("terminated_at"),
                        "到期归档终止时间固定为 expiresAt");
                assertNull(rs.getObject("terminate_actor"), "到期不伪造处理人");
                assertNull(rs.getObject("terminate_reason"));
                assertNull(rs.getString("treatment"), "到期不残留盲底");
                assertNull(rs.getObject("pending_allocation_id"), "归档后释放待审占位");
            }

            // 6) 迁移脚本重复执行必须幂等（DROP/ADD 约束、ADD COLUMN IF NOT EXISTS 均不报错）
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM unblind_request")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "重复迁移不得产生重复数据");
            }

            // 释放命名内存库
            try (Statement st = conn.createStatement()) {
                st.execute("SHUTDOWN");
            }
        }
    }
}
