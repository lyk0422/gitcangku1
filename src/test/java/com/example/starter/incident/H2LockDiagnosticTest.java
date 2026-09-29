package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 直接以原生 JDBC 验证 H2（MODE=MySQL）下通报回执的两条底层保证：
 * 1) 写路径先 SELECT ... FOR UPDATE 锁事件行时，并发同键插入被行锁串行化；
 * 2) request_id 全局唯一约束裁决并发插入：恰好 1 份成功，其余报唯一约束冲突，
 *    最终表中只有 1 行。
 * 使用独立命名内存库（DB_CLOSE_DELAY=-1 供工作线程共享，随 JVM 退出释放），不依赖外部数据库。
 */
class H2LockDiagnosticTest {

    @Test
    void forUpdateSerializesAndUniqueRequestIdWinsOnce() throws Exception {
        String url = "jdbc:h2:mem:diag_lock_ack;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        // 主连接负责建表与种子数据，并在整个用例期间持有，保证内存库存活
        try (Connection owner = DriverManager.getConnection(url, "sa", "")) {
            ScriptUtils.executeSqlScript(owner, new ClassPathResource("schema-h2.sql"));
            Instant now = Instant.parse("2026-09-29T11:00:00Z");
            try (Statement st = owner.createStatement()) {
                st.execute("INSERT INTO incidents (incident_key, severity, summary, reporter, status,"
                        + " commander, created_at, updated_at) VALUES"
                        + " ('IK','S1','s','r','COMMANDING','a','" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_notifications (incident_id, escalation_id,"
                        + " created_at, updated_at) VALUES (1,1,'" + Timestamp.from(now) + "','"
                        + Timestamp.from(now) + "')");
                st.execute("INSERT INTO incident_notification_agencies (notification_id, agency_key,"
                        + " critical, ack_deadline, created_at, updated_at) VALUES"
                        + " (1,'jia',TRUE,'" + Timestamp.from(now) + "','" + Timestamp.from(now)
                        + "','" + Timestamp.from(now) + "')");
            }

            int n = 4;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                CountDownLatch ready = new CountDownLatch(n);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try (Connection con = DriverManager.getConnection(url, "sa", "")) {
                            con.setAutoCommit(false);
                            try (Statement st = con.createStatement()) {
                                try (ResultSet rs = st.executeQuery(
                                        "SELECT id FROM incidents WHERE incident_key='IK' FOR UPDATE")) {
                                    rs.next();
                                }
                                try {
                                    st.execute("INSERT INTO incident_notification_acks"
                                            + " (agency_notification_id, request_id, signer, content,"
                                            + " received_at, created_at) VALUES"
                                            + " (1,'REQ-X','zhang','c','" + Timestamp.from(now)
                                            + "','" + Timestamp.from(now) + "')");
                                    con.commit();
                                    return "INSERTED";
                                } catch (Exception e) {
                                    con.rollback();
                                    return "DUP";
                                }
                            }
                        }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(outcomes).filteredOn("INSERTED"::equals).hasSize(1);
                assertThat(outcomes).filteredOn("DUP"::equals).hasSize(3);
            } finally {
                pool.shutdownNow();
            }

            try (Statement st = owner.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM incident_notification_acks WHERE request_id='REQ-X'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
