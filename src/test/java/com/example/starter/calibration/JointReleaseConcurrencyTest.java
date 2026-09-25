package com.example.starter.calibration;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 联合批次放行并发与幂等边界测试：
 * 1. 两个联合批次共享同一测量时最多一个成功（竞态多轮 + 行锁协调的确定性 409）；
 * 2. 校验通过后、提交前证书被撤销时整批 409（行锁协调的确定性交织）；
 * 3. 撤销与联合放行并发按事务提交顺序裁决，不出现部分放行；
 * 4. 并发同键同参请求幂等重放同一快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointReleaseConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

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
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, by)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
    }

    private int jointRelease(String batchKey, String keysJson, String actor) throws Exception {
        return mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"" + batchKey + "\",\"keys\":" + keysJson + "}"))
                .andReturn().getResponse().getStatus();
    }

    /**
     * 等待线程稳定进入锁等待状态（连续多次采样均为 WAITING/TIMED_WAITING），超时则失败。
     */
    private static void awaitLockWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int stable = 0;
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                stable++;
                if (stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("等待工作线程进入数据库行锁等待超时");
    }

    @Test
    void sharedMeasurementBetweenJointBatchesExactlyOneSucceeds() throws Exception {
        AtomicInteger conflict409 = new AtomicInteger();
        for (int round = 0; round < 5; round++) {
            final int r = round;
            createCert("INS-S" + r + "A");
            createCert("INS-S" + r + "B");
            submit("SM-" + r + "-1", "INS-S" + r + "A", "alice");
            submit("SM-" + r + "-2", "INS-S" + r + "A", "alice");
            submit("SM-" + r + "-3", "INS-S" + r + "B", "bob");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> batchA = pool.submit(() -> {
                start.await();
                return jointRelease("JB-S" + r + "A",
                        "[\"SM-" + r + "-1\",\"SM-" + r + "-2\"]", "carol");
            });
            Future<Integer> batchB = pool.submit(() -> {
                start.await();
                return jointRelease("JB-S" + r + "B",
                        "[\"SM-" + r + "-2\",\"SM-" + r + "-3\"]", "carol");
            });
            start.countDown();
            int statusA = batchA.get(30, TimeUnit.SECONDS);
            int statusB = batchB.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            int successes = (statusA == 200 ? 1 : 0) + (statusB == 200 ? 1 : 0);
            assertEquals(1, successes, "共享同一测量的两个联合批次必须恰好一个成功");
            int loser = statusA == 200 ? statusB : statusA;
            assertTrue(loser == 409 || loser == 422, "失败批次必须整体拒绝（409 并发冲突或 422 校验失败）");
            if (loser == 409) {
                conflict409.incrementAndGet();
            }

            // 共享测量只被放行一次；不存在部分放行
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, "SM-" + round + "-2"));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurement WHERE measurement_key LIKE 'SM-" + round
                            + "-%' AND status = 'RELEASED'", Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_batch WHERE joint_batch_key LIKE 'JB-S"
                            + round + "%'", Integer.class));
        }
        assertTrue(conflict409.get() >= 1, "多轮竞态中至少一次以 409 并发冲突裁决");
    }

    @Test
    void measurementReleasedBetweenCheckAndCommitYields409() throws Exception {
        createCert("INS-C1");
        createCert("INS-C2");
        submit("C-1", "INS-C1", "alice");
        submit("C-2", "INS-C2", "bob");

        CountDownLatch locked = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<String> body = new AtomicReference<>();
        Thread releaseThread = new Thread(() -> {
            try {
                worker.set(Thread.currentThread());
                locked.await();
                var result = mvc.perform(post("/api/joint-releases")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"jointBatchKey\":\"JB-C\",\"keys\":[\"C-1\",\"C-2\"]}"))
                        .andReturn();
                status.set(result.getResponse().getStatus());
                body.set(result.getResponse().getContentAsString());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        releaseThread.start();
        while (worker.get() == null) {
            Thread.sleep(10);
        }

        // 主线程事务：先锁住 C-1 行，等联合放行线程在校验通过后阻塞在该行锁上，
        // 再模拟另一批次将 C-1 放行并提交
        new TransactionTemplate(txManager).execute(tx -> {
            jdbc.queryForObject("SELECT id FROM measurement WHERE measurement_key = ? FOR UPDATE",
                    Long.class, "C-1");
            locked.countDown();
            try {
                awaitLockWait(worker.get());
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
            jdbc.update("UPDATE measurement SET status = 'RELEASED' WHERE measurement_key = ?", "C-1");
            return null;
        });
        releaseThread.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(409, status.get(), "校验通过后测量被并发放行必须整批 409");
        assertTrue(body.get().contains("CONCURRENT_RELEASE_CONFLICT"));
        // 整批回滚：C-2 未放行，无联合批次记录与放行历史
        Map<String, Object> c2 = jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key = 'C-2'");
        assertEquals("PENDING", c2.get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'C-2'", Integer.class));
    }

    @Test
    void certificateRevokedBetweenCheckAndCommitYields409() throws Exception {
        long certId = createCert("INS-V");
        submit("V-1", "INS-V", "alice");
        submit("V-2", "INS-V", "bob");

        CountDownLatch locked = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(-1);
        AtomicReference<String> body = new AtomicReference<>();
        Thread releaseThread = new Thread(() -> {
            try {
                worker.set(Thread.currentThread());
                locked.await();
                var result = mvc.perform(post("/api/joint-releases")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"jointBatchKey\":\"JB-V\",\"keys\":[\"V-1\",\"V-2\"]}"))
                        .andReturn();
                status.set(result.getResponse().getStatus());
                body.set(result.getResponse().getContentAsString());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        releaseThread.start();
        while (worker.get() == null) {
            Thread.sleep(10);
        }

        // 主线程事务：先锁住证书行，等联合放行线程在校验通过后阻塞在证书行锁上，再撤销并提交
        new TransactionTemplate(txManager).execute(tx -> {
            jdbc.queryForObject("SELECT id FROM calibration_certificate WHERE id = ? FOR UPDATE",
                    Long.class, certId);
            locked.countDown();
            try {
                awaitLockWait(worker.get());
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
            jdbc.update("UPDATE calibration_certificate SET revoked = TRUE, revoked_at = ? WHERE id = ?",
                    LocalDateTime.now(ZoneOffset.UTC), certId);
            return null;
        });
        releaseThread.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(409, status.get(), "校验通过后证书被并发撤销必须整批 409");
        assertTrue(body.get().contains("CONCURRENT_CERTIFICATE_REVOKED"));
        // 整批回滚：两条测量均保持待放行，无任何记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE status = 'RELEASED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void revokeAndJointReleaseConcurrentFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int r = round;
            long certId = createCert("INS-RJ" + r);
            submit("RJ-" + r + "-1", "INS-RJ" + r, "alice");
            submit("RJ-" + r + "-2", "INS-RJ" + r, "bob");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return jointRelease("JB-RJ" + r,
                        "[\"RJ-" + r + "-1\",\"RJ-" + r + "-2\"]", "carol");
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409 || releaseStatus == 422,
                    "联合放行只能整体成功或整体失败: " + releaseStatus);

            Integer releasedCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurement WHERE measurement_key LIKE 'RJ-" + round
                            + "-%' AND status = 'RELEASED'", Integer.class);
            Integer batchCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM joint_release_batch WHERE joint_batch_key = ?",
                    Integer.class, "JB-RJ" + round);
            if (releaseStatus == 200) {
                // 放行先提交：整批成功，记录完整；撤销随后生效不回写历史
                assertEquals(2, releasedCount);
                assertEquals(1, batchCount);
                assertEquals(2, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                                + "WHERE m.measurement_key LIKE 'RJ-" + round + "-%'", Integer.class));
                Map<String, Object> cert = jdbc.queryForMap(
                        "SELECT revoked_at FROM calibration_certificate WHERE id = ?", certId);
                Map<String, Object> release = jdbc.queryForMap(
                        "SELECT released_at FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                                + "WHERE m.measurement_key = ?", "RJ-" + round + "-1");
                assertFalse(((java.sql.Timestamp) release.get("released_at"))
                        .after((java.sql.Timestamp) cert.get("revoked_at")), "撤销提交后不得再放行");
            } else {
                // 撤销先提交：整批失败，无部分放行、不占键
                assertEquals(0, releasedCount);
                assertEquals(0, batchCount);
            }
        }
    }

    @Test
    void concurrentSameKeySameParamsReplaySameSnapshot() throws Exception {
        createCert("INS-K1");
        createCert("INS-K2");
        submit("K-1", "INS-K1", "alice");
        submit("K-2", "INS-K2", "bob");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            boolean reversed = i % 2 == 1;
            results.add(pool.submit(() -> {
                start.await();
                String keys = reversed ? "[\"K-2\",\"K-1\"]" : "[\"K-1\",\"K-2\"]";
                return mvc.perform(post("/api/joint-releases")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"jointBatchKey\":\"JB-K\",\"keys\":" + keys + "}"))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<String> future : results) {
            bodies.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部 200 且响应快照一致（换序视为同参）
        String first = bodies.get(0);
        assertEquals("JB-K", com.jayway.jsonpath.JsonPath.read(first, "$.jointBatchKey"));
        Object firstReleasedAt = com.jayway.jsonpath.JsonPath.read(first, "$.releasedAt");
        Object firstReleased = com.jayway.jsonpath.JsonPath.read(first, "$.released");
        for (String bodyJson : bodies) {
            assertEquals(firstReleasedAt, com.jayway.jsonpath.JsonPath.read(bodyJson, "$.releasedAt"));
            assertEquals(firstReleased, com.jayway.jsonpath.JsonPath.read(bodyJson, "$.released"));
        }
        // 幂等：只放行一次
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }
}
