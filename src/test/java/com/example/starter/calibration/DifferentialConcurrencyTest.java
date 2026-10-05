package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差分并发与幂等边界测试：
 * 1. 并发同 requestId 同参创建：全部重放为同一响应，仅一条差分记录；
 * 2. 并发同 requestId 异参创建：恰一次成功，其余 409；
 * 3. 并发配对同一测量：恰一条差分成功；
 * 4. 并发放行与取消：恰一个成功，最终状态一致；
 * 5. 差分放行与批量放行并发：批量必须被差分门禁拒绝；
 * 6. 差分放行与证书撤销并发：按事务提交顺序生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifferentialConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM differential_occupation");
        jdbc.update("DELETE FROM differential");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String measuredAt, String reading,
                        String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"100","submittedBy":"%s"}
                                """.formatted(key, instrument, measuredAt, reading, by)))
                .andExpect(status().isCreated());
    }

    private String createDiffBody(String requestId, String key, String ref, String dut, String upper) {
        return """
                {"requestId":"%s","differentialKey":"%s","referenceKey":"%s","dutKey":"%s",
                 "lowerLimit":"0","upperLimit":"%s"}
                """.formatted(requestId, key, ref, dut, upper);
    }

    private MvcResult createDiff(String requestId, String key, String ref, String dut,
                                 String upper, String actor) throws Exception {
        return mvc.perform(post("/api/differentials").header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createDiffBody(requestId, key, ref, dut, upper)))
                .andReturn();
    }

    private int releaseDiffStatus(String key, String requestId, String actor) throws Exception {
        return mvc.perform(post("/api/differentials/{key}/release", key).header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int cancelDiffStatus(String key, String requestId, String actor) throws Exception {
        return mvc.perform(post("/api/differentials/{key}/cancel", key).header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private long countOf(List<Integer> statuses, int expected) {
        return statuses.stream().filter(s -> s == expected).count();
    }

    @Test
    void concurrentSameRequestIdSameParamsReplaysSingleResponse() throws Exception {
        createCert("INS-C1", "1", "0");
        submit("C1-REF", "INS-C1", "2026-06-01T00:00:00Z", "9.8", "alice");
        submit("C1-DUT", "INS-C1", "2026-06-01T00:00:05Z", "10.1", "bob");
        String body = createDiffBody("RQ-CC1", "DIFF-CC1", "C1-REF", "C1-DUT", "0.3");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/differentials").header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn();
            }));
        }
        start.countDown();
        Set<String> bodies = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertEquals(201, result.getResponse().getStatus(), "并发同键同参必须全部得到成功响应");
            bodies.add(result.getResponse().getContentAsString());
        }
        pool.shutdown();
        assertEquals(1, bodies.size(), "所有并发响应必须与首次完整响应一致");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM differential", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM differential_occupation", Integer.class));
    }

    @Test
    void concurrentSameRequestIdDifferentParamsExactlyOneSucceeds() throws Exception {
        createCert("INS-C2", "1", "0");
        submit("C2-REF", "INS-C2", "2026-06-01T00:00:00Z", "9.8", "alice");
        submit("C2-DUT", "INS-C2", "2026-06-01T00:00:05Z", "10.1", "bob");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = pool.submit(() -> {
            start.await();
            return createDiff("RQ-CC2", "DIFF-CC2", "C2-REF", "C2-DUT", "0.3", "carol")
                    .getResponse().getStatus();
        });
        Future<Integer> second = pool.submit(() -> {
            start.await();
            return createDiff("RQ-CC2", "DIFF-CC2", "C2-REF", "C2-DUT", "0.4", "carol")
                    .getResponse().getStatus();
        });
        start.countDown();
        int statusFirst = first.get(30, TimeUnit.SECONDS);
        int statusSecond = second.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue((statusFirst == 201 && statusSecond == 409)
                        || (statusFirst == 409 && statusSecond == 201),
                "同键异参并发必须恰一次成功，实际: " + statusFirst + "/" + statusSecond);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM differential", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class));
    }

    @Test
    void concurrentPairingSameMeasurementExactlyOneWins() throws Exception {
        createCert("INS-C3", "1", "0");
        submit("C3-REF", "INS-C3", "2026-06-01T00:00:00Z", "9.8", "alice");
        int threads = 6;
        for (int i = 0; i < threads; i++) {
            submit("C3-DUT-" + i, "INS-C3", "2026-06-01T00:00:05Z", "10.1", "bob");
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return createDiff("RQ-CC3-" + index, "DIFF-CC3-" + index, "C3-REF",
                        "C3-DUT-" + index, "0.3", "carol").getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, countOf(statuses, 201), "并发配对同一测量必须恰一条差分成功");
        assertEquals(threads - 1, countOf(statuses, 409));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM differential", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM differential_occupation", Integer.class));
    }

    @Test
    void concurrentReleaseAndCancelExactlyOneWins() throws Exception {
        createCert("INS-C4", "1", "0");
        submit("C4-REF", "INS-C4", "2026-06-01T00:00:00Z", "9.8", "alice");
        submit("C4-DUT", "INS-C4", "2026-06-01T00:00:05Z", "10.1", "bob");
        assertEquals(201, createDiff("RQ-CC4", "DIFF-CC4", "C4-REF", "C4-DUT", "0.3", "carol")
                .getResponse().getStatus());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> release = pool.submit(() -> {
            start.await();
            return releaseDiffStatus("DIFF-CC4", "RQ-CC4-R", "dave");
        });
        Future<Integer> cancel = pool.submit(() -> {
            start.await();
            return cancelDiffStatus("DIFF-CC4", "RQ-CC4-C", "carol");
        });
        start.countDown();
        int releaseStatus = release.get(30, TimeUnit.SECONDS);
        int cancelStatus = cancel.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        Map<String, Object> diff = jdbc.queryForMap(
                "SELECT * FROM differential WHERE differential_key = 'DIFF-CC4'");
        String refStatus = jdbc.queryForObject(
                "SELECT status FROM measurement WHERE measurement_key = 'C4-REF'", String.class);
        if (releaseStatus == 200) {
            assertEquals(409, cancelStatus, "放行成功后取消必须 409");
            assertEquals("RELEASED", diff.get("status"));
            assertEquals("RELEASED", refStatus);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        } else {
            assertEquals(409, releaseStatus);
            assertEquals(200, cancelStatus);
            assertEquals("CANCELLED", diff.get("status"));
            assertEquals("PENDING", refStatus);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM differential_occupation", Integer.class));
        }
    }

    @Test
    void batchReleaseCannotBypassDifferentialGate() throws Exception {
        createCert("INS-C5", "1", "0");
        // 键字典序与插入顺序一致，使批量放行与差分放行的行锁顺序相同，避免锁顺序差异
        submit("C5-K1", "INS-C5", "2026-06-01T00:00:00Z", "9.8", "alice");
        submit("C5-K2", "INS-C5", "2026-06-01T00:00:05Z", "10.1", "bob");
        assertEquals(201, createDiff("RQ-CC5", "DIFF-CC5", "C5-K1", "C5-K2", "0.3", "carol")
                .getResponse().getStatus());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> diffRelease = pool.submit(() -> {
            start.await();
            return releaseDiffStatus("DIFF-CC5", "RQ-CC5-R", "dave");
        });
        Future<Integer> batchRelease = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keys\":[\"C5-K1\",\"C5-K2\"]}"))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        int diffStatus = diffRelease.get(30, TimeUnit.SECONDS);
        int batchStatus = batchRelease.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(200, diffStatus, "差分放行必须成功");
        assertEquals(409, batchStatus, "批量放行不得绕过差分门禁");
        // 两条测量仅经差分放行各写一条历史
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = 'DIFF-DIFF-CC5'", Integer.class));
    }

    @Test
    void concurrentReleaseAndRevokeFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            String suffix = "-" + round;
            long certId = createCert("INS-C6" + suffix, "1", "0");
            submit("C6-REF" + suffix, "INS-C6" + suffix, "2026-06-01T00:00:00Z", "9.8", "alice");
            submit("C6-DUT" + suffix, "INS-C6" + suffix, "2026-06-01T00:00:05Z", "10.1", "bob");
            assertEquals(201, createDiff("RQ-CC6" + suffix, "DIFF-CC6" + suffix,
                    "C6-REF" + suffix, "C6-DUT" + suffix, "0.3", "carol").getResponse().getStatus());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revoke = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> release = pool.submit(() -> {
                start.await();
                return releaseDiffStatus("DIFF-CC6" + suffix, "RQ-CC6-R" + suffix, "dave");
            });
            start.countDown();
            int revokeStatus = revoke.get(30, TimeUnit.SECONDS);
            int releaseStatus = release.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409,
                    "差分放行只能成功或被整次拒绝，实际: " + releaseStatus);

            Map<String, Object> diff = jdbc.queryForMap(
                    "SELECT * FROM differential WHERE differential_key = ?", "DIFF-CC6" + suffix);
            String refStatus = jdbc.queryForObject(
                    "SELECT status FROM measurement WHERE measurement_key = ?", String.class,
                    "C6-REF" + suffix);
            if (releaseStatus == 200) {
                // 放行先提交：双方已放行，撤销在其后生效，证据保留
                assertEquals("RELEASED", diff.get("status"));
                assertEquals("RELEASED", refStatus);
            } else {
                // 撤销先提交：整次 409，双方保持待放行
                assertEquals("PENDING", diff.get("status"));
                assertEquals("PENDING", refStatus);
            }
            clean();
        }
    }
}