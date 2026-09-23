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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修订并发边界测试：
 * 1. 同 expectedRevision 并发修订同一测量键时最多一次成功，修订号不重复、最新指针唯一；
 * 2. 并发版本化放行同一版本时恰好一次成功；
 * 3. 修订与版本化放行并发时按事务提交顺序裁决，绝不放行过期版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevisionConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentRevisionsWithSameExpectedRevisionAtMostOneSucceeds() throws Exception {
        createCert("INS-C");
        submit("M-C", "INS-C");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revisions", "M-C")
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"expectedRevision":1,"requestId":"REQ-%d","reason":"并发修订",
                                         "reading":"2","lowerLimit":"0","upperLimit":"9"}
                                        """.formatted(index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            statuses.add(status);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "同 expectedRevision 并发修订必须恰好一次成功, 全部状态=" + statuses);
        assertEquals(threads - 1, conflict.get(), "其余必须为 409 冲突, 全部状态=" + statuses);

        // 最终数据：仅两版（首版 + 一个修订版），修订号唯一、最新指针唯一
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-C'", Integer.class);
        assertEquals(2, count);
        Integer latestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-C' AND is_latest = TRUE",
                Integer.class);
        assertEquals(1, latestCount);
        Integer maxRevision = jdbc.queryForObject(
                "SELECT MAX(revision) FROM measurement WHERE measurement_key = 'M-C'", Integer.class);
        assertEquals(2, maxRevision);
    }

    @Test
    void concurrentVersionedReleaseSameRevisionExactlyOneWins() throws Exception {
        createCert("INS-V");
        submit("M-V", "INS-V");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release-versions")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"items\":[{\"measurementKey\":\"M-V\",\"revision\":1}]}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发版本化放行必须恰好一次成功");
        assertEquals(threads - 1, conflict.get());
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'M-V'", Integer.class);
        assertEquals(1, releaseCount, "只允许写入一条放行历史");
    }

    @Test
    void reviseAndVersionedReleaseConcurrentNeverReleaseStaleVersion() throws Exception {
        // 重复多轮，验证任意调度下都不出现“过期版本被放行进入当前可用集合”
        for (int round = 0; round < 10; round++) {
            final int r = round;
            createCert("INS-X" + r);
            submit("M-X" + r, "INS-X" + r);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> reviseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/{key}/revisions", "M-X" + r)
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"expectedRevision":1,"requestId":"REQ-X","reason":"并发修订",
                                         "reading":"2","lowerLimit":"0","upperLimit":"9"}
                                        """))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release-versions")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"items\":[{\"measurementKey\":\"M-X" + r
                                        + "\",\"revision\":1}]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 修订总是成功（修订不依赖待放行状态）
            assertEquals(200, reviseStatus, "第 " + r + " 轮修订应成功");
            assertTrue(releaseStatus == 200 || releaseStatus == 409,
                    "放行只能是成功或整批拒绝，实际: " + releaseStatus);

            Map<String, Object> v1 = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ? AND revision = 1", "M-X" + r);
            Map<String, Object> v2 = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ? AND revision = 2", "M-X" + r);
            assertEquals(Boolean.TRUE, v2.get("is_latest"));
            assertEquals(Boolean.FALSE, v1.get("is_latest"));

            Integer usableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurement m JOIN calibration_certificate c "
                            + "ON c.id = m.certificate_id WHERE m.measurement_key = ? "
                            + "AND m.is_latest = TRUE AND m.status = 'RELEASED' AND c.revoked = FALSE",
                    Integer.class, "M-X" + r);
            if (releaseStatus == 200) {
                // 放行先提交：首版已放行并保留历史，但新版提交后首版不再是当前可用
                assertEquals("RELEASED", v1.get("status"));
                assertEquals(0, usableCount, "新版未放行时旧版不得留在当前可用集合");
            } else {
                // 修订先提交：放行过期版本必须被 409 拒绝，首版保持原状态
                assertEquals("PENDING", v1.get("status"));
                assertEquals("PENDING", v2.get("status"));
                assertEquals(0, usableCount);
            }
        }
    }
}
