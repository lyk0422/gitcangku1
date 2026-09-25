package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 跨仪器联合批次并发与幂等边界测试（真实 H2 内存库，MODE=MySQL，协调真实并发并设置超时）：
 * 1. 两个联合批次共享同一条测量时最多一个成功，另一个 409，无部分放行；
 * 2. 证书撤销与联合批次放行并发时按事务提交顺序裁决，不产生部分放行；
 * 3. 同 requestId 同参并发重放只产生一条成功记录并返回一致快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointReleaseConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM joint_release_item");
        jdbc.update("DELETE FROM joint_release_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(instrument)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}"""
                                .formatted(key, instrument, by)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private int jointStatus(String actor, String body) throws Exception {
        return mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void overlappingJointBatchesAtMostOneReleasesSharedMeasurement() throws Exception {
        createCert("INS-X");
        createCert("INS-Y");
        // 批次1: [X-1, Y-1]；批次2: [Y-1, Y-2]，共享 Y-1
        submit("X-1", "INS-X", "alice");
        submit("Y-1", "INS-Y", "bob");
        submit("Y-2", "INS-Y", "carol");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> f1 = pool.submit(() -> {
                start.await();
                return jointStatus("dave",
                        "{\"jointBatchKey\":\"JB-1\",\"requestId\":\"R1\",\"keys\":[\"X-1\",\"Y-1\"]}");
            });
            Future<Integer> f2 = pool.submit(() -> {
                start.await();
                return jointStatus("dave",
                        "{\"jointBatchKey\":\"JB-2\",\"requestId\":\"R2\",\"keys\":[\"Y-1\",\"Y-2\"]}");
            });
            start.countDown();
            int s1 = f1.get(30, TimeUnit.SECONDS);
            int s2 = f2.get(30, TimeUnit.SECONDS);

            // 共享测量 Y-1 最多被一个批次放行：恰好一个 200，另一个 409
            int ok = (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0);
            int conflict = (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0);
            assertEquals(1, ok, "共享测量的两个联合批次必须恰好一个成功");
            assertEquals(1, conflict, "失败批次必须返回 409");
        } finally {
            pool.shutdown();
        }

        // 最终数据：Y-1 恰好一条联合明细、一条放行历史；X-1 与 Y-2 中仅成功批次对应的测量被放行
        Map<String, Object> y1 = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'Y-1'");
        assertEquals("RELEASED", y1.get("status"));
        long y1Id = ((Number) y1.get("id")).longValue();
        Integer history = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?", Integer.class, y1Id);
        Integer jointItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_item WHERE measurement_id = ?", Integer.class, y1Id);
        assertEquals(1, history, "共享测量只允许一条放行历史");
        assertEquals(1, jointItems, "共享测量只允许一条联合明细");

        String statusX1 = jdbc.queryForObject(
                "SELECT status FROM measurement WHERE measurement_key = 'X-1'", String.class);
        String statusY2 = jdbc.queryForObject(
                "SELECT status FROM measurement WHERE measurement_key = 'Y-2'", String.class);
        // 成功批次整体生效、失败批次整体回滚：X-1 与 Y-2 的放行状态必然相反
        assertFalse(statusX1.equals(statusY2) && statusX1.equals("RELEASED"),
                "不得出现两个批次都成功");
        assertTrue("RELEASED".equals(statusX1) || "RELEASED".equals(statusY2),
                "成功批次内全部测量必须被放行");
        assertTrue("PENDING".equals(statusX1) || "PENDING".equals(statusY2),
                "失败批次不得产生部分放行");

        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_batch", Integer.class);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_item", Integer.class);
        assertEquals(1, batchCount, "失败批次整批回滚，只落地一个联合批次");
        assertEquals(2, itemCount, "成功批次固化两条明细");
    }

    @Test
    void revokeCommittingAfterValidationBeforeCommitFailsWholeBatch409() throws Exception {
        // 确定性制造“批次校验通过后、实际放行提交前关联证书被撤销”的交错：
        // 协调线程先持有证书行的 FOR UPDATE 锁；放行事务在锁完测量行后必然阻塞在证书行锁上；
        // 此时协调线程提交撤销，放行事务获锁后读到已撤销证书，必须整批 409、无部分放行。
        String insA = "INS-LA";
        String insB = "INS-LB";
        long certA = createCert(insA);
        createCert(insB);
        submit("LA-1", insA, "alice");
        submit("LB-1", insB, "bob");

        javax.sql.DataSource ds = jdbc.getDataSource();
        java.sql.Connection holder = ds.getConnection();
        holder.setAutoCommit(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        try {
            // 协调线程：持有 certA 行锁 → 通知主线程 → 等待放行事务到达锁等待 → 提交撤销
            Future<?> holderTask = pool.submit(() -> {
                try {
                    try (java.sql.PreparedStatement ps = holder.prepareStatement(
                            "SELECT id FROM calibration_certificate WHERE id = ? FOR UPDATE")) {
                        ps.setLong(1, certA);
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            assertTrue(rs.next());
                        }
                        lockHeld.countDown();
                        // 留出有界时间让放行事务完成测量行加锁并阻塞在本证书行锁上
                        assertTrue(mayCommit.await(10, TimeUnit.SECONDS));
                        try (java.sql.PreparedStatement up = holder.prepareStatement(
                                "UPDATE calibration_certificate SET revoked = TRUE, revoked_at = ? WHERE id = ?")) {
                            // 与应用约定一致：DATETIME(6) 存 UTC 墙钟时间，避免 Timestamp 被按 JVM 本地时区解释
                            up.setObject(1, java.time.LocalDateTime.ofInstant(
                                    java.time.Instant.now(), java.time.ZoneOffset.UTC));
                            up.setLong(2, certA);
                            up.executeUpdate();
                        }
                        holder.commit();
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });

            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "协调线程必须先持有证书行锁");

            // 放行线程在证书行锁上阻塞，直到撤销提交
            Future<Integer> releaseFuture = pool.submit(() -> jointStatus("carol",
                    "{\"jointBatchKey\":\"JB-L\",\"requestId\":\"REQ-L\",\"keys\":[\"LA-1\",\"LB-1\"]}"));

            // 有界协调：确认放行请求已进入事务后，放行线程等待证书锁，再让撤销提交
            Thread.sleep(500);
            mayCommit.countDown();
            holderTask.get(10, TimeUnit.SECONDS);

            int status = releaseFuture.get(10, TimeUnit.SECONDS);
            assertEquals(409, status, "校验通过后证书被撤销必须整批 409");

            // 无部分放行：两条测量都保持 PENDING，且不落地任何联合/放行记录
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM measurement WHERE measurement_key = 'LA-1'", String.class));
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM measurement WHERE measurement_key = 'LB-1'", String.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_batch", Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_item", Integer.class));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record", Integer.class));

            // 失败不占键：撤销已既成事实后用同键重提仍按业务 422 被拒（而不是键冲突）
            int retry = jointStatus("carol",
                    "{\"jointBatchKey\":\"JB-L\",\"requestId\":\"REQ-L\",\"keys\":[\"LA-1\",\"LB-1\"]}");
            assertEquals(422, retry, "失败不占键，证书已撤销时重提返回业务 422");
        } finally {
            pool.shutdown();
            holder.rollback();
            holder.close();
        }
    }

    @Test
    void revokeAndJointReleaseConcurrentlyNoPartialRelease() throws Exception {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int round = 0; round < 15; round++) {
            String insA = "INS-RA" + round;
            String insB = "INS-RB" + round;
            long certA = createCert(insA);
            createCert(insB);
            submit("RA-" + round, insA, "alice");
            submit("RB-" + round, insB, "bob");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            final long certId = certA;
            final int r = round;
            Future<Integer> revoke = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> release = pool.submit(() -> {
                start.await();
                return jointStatus("carol",
                        "{\"jointBatchKey\":\"JB-R%d\",\"requestId\":\"REQ-R%d\",\"keys\":[\"RA-%d\",\"RB-%d\"]}"
                                .formatted(r, r, r, r));
            });
            start.countDown();
            int revokeStatus = revoke.get(30, TimeUnit.SECONDS);
            int releaseStatus = release.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409 || releaseStatus == 422,
                    "联合放行只能成功(200)、并发冲突(409)或业务失效(422)，实际 " + releaseStatus);
            if (releaseStatus == 200) {
                ok.incrementAndGet();
            } else if (releaseStatus == 409) {
                conflict.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }

            String statusA = jdbc.queryForObject(
                    "SELECT status FROM measurement WHERE measurement_key = ?",
                    String.class, "RA-" + round);
            String statusB = jdbc.queryForObject(
                    "SELECT status FROM measurement WHERE measurement_key = ?",
                    String.class, "RB-" + round);
            Integer batches = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_batch WHERE joint_batch_key = ?",
                    Integer.class, "JB-R" + round);
            Integer items = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_item WHERE joint_batch_key = ?",
                    Integer.class, "JB-R" + round);

            if (releaseStatus == 200) {
                // 放行先提交：两条都放行，联合记录固化且不被之后的撤销追溯
                assertEquals("RELEASED", statusA);
                assertEquals("RELEASED", statusB);
                assertEquals(1, batches);
                assertEquals(2, items);
            } else {
                // 撤销先于校验完成（422）或在通过后提交（409）：都必须整批失败、无部分放行、不占键
                assertEquals("PENDING", statusA, "证书撤销后联合批次不得放行该测量");
                assertEquals("PENDING", statusB, "另一条仪器的测量不得被部分放行");
                assertEquals(0, batches, "失败不占联合批次键");
                assertEquals(0, items, "失败不得写入明细快照");
            }
        }
        // 多轮调度下应至少观察到一次“放行先提交成功”；冲突分支由确定性测试专门覆盖
        assertTrue(ok.get() > 0, "撤销与放行并发时放行先提交的顺序应被实际观察到");
    }

    @Test
    void concurrentSameRequestIdSameParamsReplaysSingleSuccess() throws Exception {
        createCert("INS-P");
        createCert("INS-Q");
        submit("P-1", "INS-P", "alice");
        submit("Q-1", "INS-Q", "bob");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return jointStatus("carol",
                        "{\"jointBatchKey\":\"JB-P\",\"requestId\":\"REQ-P\",\"keys\":[\"P-1\",\"Q-1\"]}");
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(threads, ok.get(), "同 requestId 同参并发重放应全部返回首次成功快照 200");
        assertEquals(0, other.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class),
                "并发重放只允许落地一条联合批次记录");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }
}
