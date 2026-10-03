package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

/**
 * 离群复核并发测试：并发复核按提交顺序裁决且裁决恰好形成一次；
 * 并发放行恰好一个成功；并发同 requestId 重放首次结果且只占一键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutlierConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outlier_audit");
        jdbc.update("DELETE FROM batch_stats_record");
        jdbc.update("DELETE FROM batch_release_snapshot");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_reading");
        jdbc.update("DELETE FROM calibration_batch");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long setupOutlier() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-B","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-1","batchKey":"B-1","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        String body = mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R-1","readingKey":"RD-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.6","submittedBy":"alice"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();
    }

    /**
     * 并发执行若干任务并收集 HTTP 状态码；闩锁保证同时起跑，超时防止悬挂。
     */
    private List<Integer> runConcurrent(List<java.util.concurrent.Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (var task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务超时");
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private java.util.concurrent.Callable<Integer> reviewTask(long outlierId, String reviewer,
                                                              String decision) {
        return () -> mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-RV-%s","reviewer":"%s","decision":"%s",
                                 "reason":"并发复核","batchRevision":1,"certificateVersion":1}
                                """.formatted(reviewer, reviewer, decision)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void concurrentAcceptReviewsFormExactlyOneVerdict() throws Exception {
        long outlierId = setupOutlier();
        List<Integer> statuses = runConcurrent(List.of(
                reviewTask(outlierId, "bob", "ACCEPT"),
                reviewTask(outlierId, "carol", "ACCEPT")));
        assertEquals(List.of(201, 201), statuses.stream().sorted().toList());

        // 两名复核人均成功，裁决恰好形成一次
        Integer validReviews = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_review WHERE status = 'VALID'", Integer.class);
        assertEquals(2, validReviews);
        String outlierStatus = jdbc.queryForObject(
                "SELECT status FROM outlier_record WHERE id = " + outlierId, String.class);
        assertEquals("ACCEPTED", outlierStatus);
        Integer verdicts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_audit WHERE event_type = 'VERDICT_ACCEPT'", Integer.class);
        assertEquals(1, verdicts);
    }

    @Test
    void concurrentConflictingReviewsKeepPendingAndBlockRelease() throws Exception {
        long outlierId = setupOutlier();
        List<Integer> statuses = runConcurrent(List.of(
                reviewTask(outlierId, "bob", "ACCEPT"),
                reviewTask(outlierId, "carol", "REJECT")));
        assertEquals(List.of(201, 201), statuses.stream().sorted().toList());

        // 结论相反：保持待仲裁，批次继续阻断
        String outlierStatus = jdbc.queryForObject(
                "SELECT status FROM outlier_record WHERE id = " + outlierId, String.class);
        assertEquals("PENDING", outlierStatus);
        mvc.perform(post("/api/batches/{key}/release", "B-1")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L-1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isUnprocessableEntity());
    }
    @Test
    void concurrentReleaseExactlyOneSucceeds() throws Exception {
        // 无离群批次：两个并发放行（不同 requestId、不同放行人）恰好一个成功
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-B","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-C-1","batchKey":"B-1","instrumentId":"INS-B",
                                 "targetValue":"10","allowedDeviation":"0.5"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(post("/api/batches/{batch}/readings", "B-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-R-1","readingKey":"RD-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.1","submittedBy":"alice"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        List<Integer> statuses = runConcurrent(List.of(
                () -> mvc.perform(post("/api/batches/{key}/release", "B-1")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"REQ-L-1\"}"))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/batches/{key}/release", "B-1")
                                .header("X-Actor-Id", "dave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"REQ-L-2\"}"))
                        .andReturn().getResponse().getStatus()));
        assertEquals(List.of(200, 409), statuses.stream().sorted().toList());

        // 恰好一个放行快照，批次状态一致
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_release_snapshot", Integer.class);
        assertEquals(1, snapshots);
        String status = jdbc.queryForObject(
                "SELECT status FROM calibration_batch WHERE batch_key = 'B-1'", String.class);
        assertEquals("RELEASED", status);
    }

    @Test
    void concurrentSameRequestIdReplaysSingleResult() throws Exception {
        // 并发同 requestId 同参创建批次：全部得到同一批次，数据库只占一键
        int threads = 6;
        List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
        List<String> bodies = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                var result = mvc.perform(post("/api/batches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"REQ-SAME-1","batchKey":"B-SAME",
                                         "instrumentId":"INS-B","targetValue":"10",
                                         "allowedDeviation":"0.5"}"""))
                        .andReturn();
                bodies.add(result.getResponse().getContentAsString());
                return result.getResponse().getStatus();
            });
        }
        List<Integer> statuses = runConcurrent(tasks);
        assertTrue(statuses.stream().allMatch(s -> s == 201), "并发同键应全部重放 201: " + statuses);

        // 所有响应为同一批次 ID，数据库只有一条批次与一条幂等记录
        List<Long> ids = bodies.stream()
                .map(b -> ((Number) com.jayway.jsonpath.JsonPath.read(b, "$.id")).longValue())
                .distinct().toList();
        assertEquals(1, ids.size());
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_batch", Integer.class);
        assertEquals(1, batches);
        Integer keys = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, keys);
    }
}