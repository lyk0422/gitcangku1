package com.example.starter.calibration;

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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * X-Request-Id 幂等语义（真实 H2）：同参重放首次结果、异参 409、失败不占键、
 * 并发同键请求恰好执行业务一次且另一请求得到相同首次结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyH2Test {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM outlier_review");
        jdbc.update("DELETE FROM outlier_record");
        jdbc.update("DELETE FROM batch_event");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_revision");
        jdbc.update("DELETE FROM measurement_batch");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void cert() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void sameRequestIdReplaysFirstResultWithoutReExecuting() throws Exception {
        cert();
        String body = """
                {"batchKey":"B-IDEM","instrumentId":"INS-1","targetValue":"5",
                 "tolerance":"1","createdBy":"zoe"}""";
        MvcResult first = mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        MvcResult replay = mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同参重放必须返回首次响应原文");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_batch", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class));

        // 离群复核同参重放：不会重复插入复核
        mvc.perform(post("/api/batches/B-IDEM/readings").header("X-Request-Id", "REQ-R")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-1","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
        String outlier = JsonPath.read(
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/batches/{b}", "B-IDEM")).andReturn().getResponse().getContentAsString(),
                "$.outliers[0].outlierKey");
        String reviewBody = "{\"decision\":\"ACCEPT\",\"reason\":\"ok\"}";
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .header("X-Request-Id", "REQ-V").contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody))
                .andExpect(status().isOk());
        mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                        .header("X-Request-Id", "REQ-V").contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outlier_review", Integer.class),
                "重放不得再次写入复核");
    }

    @Test
    void differentParamsWithSameRequestIdReturn409() throws Exception {
        cert();
        mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-A","instrumentId":"INS-1","targetValue":"5",
                                 "tolerance":"1","createdBy":"zoe"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-DIFFERENT","instrumentId":"INS-1","targetValue":"5",
                                 "tolerance":"1","createdBy":"zoe"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));
        // 异参请求未产生第二个批次
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_batch", Integer.class));
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        // 尚无证书：读数提交 422 失败，requestId 不应被占用
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchKey":"B-3","instrumentId":"INS-1","targetValue":"5",
                                 "tolerance":"1","createdBy":"zoe"}"""))
                .andExpect(status().isCreated());

        String reading = """
                {"measurementKey":"M-F","measuredAt":"2030-06-01T00:00:00Z",
                 "reading":"9","submittedBy":"alice"}""";
        mvc.perform(post("/api/batches/B-3/readings").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON).content(reading))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = 'REQ-FAIL'",
                Integer.class), "失败事务必须回滚幂等登记行");

        // 换成证书有效期内的时刻（同 requestId、不同参数）→ 正常执行，证明键未被占用
        mvc.perform(post("/api/batches/B-3/readings").header("X-Request-Id", "REQ-FAIL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-F","measuredAt":"2026-06-01T00:00:00Z",
                                 "reading":"9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
    }

    @Test
    void concurrentSameRequestIdExecutesBusinessExactlyOnce() throws Exception {
        cert();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        String body = """
                {"batchKey":"B-CONC","instrumentId":"INS-1","targetValue":"5",
                 "tolerance":"1","createdBy":"zoe"}""";
        java.util.List<Future<Integer>> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/batches").header("X-Request-Id", "REQ-CONC")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Integer> f : statuses) {
            int status = f.get(30, TimeUnit.SECONDS);
            assertEquals(201, status, "并发同键请求要么执行要么重放，均返回首次的 201");
            created++;
        }
        pool.shutdown();
        assertEquals(threads, created);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_batch", Integer.class),
                "业务只执行一次");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_revision", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class));
    }

    @Test
    void concurrentSameReviewerExactlyOneWins() throws Exception {
        cert();
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"batchKey":"B-UV","instrumentId":"INS-1","targetValue":"5",
                         "tolerance":"1","createdBy":"zoe"}""")).andExpect(status().isCreated());
        mvc.perform(post("/api/batches/B-UV/readings").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"M-1","measuredAt":"2026-06-01T00:00:00Z",
                         "reading":"9","submittedBy":"alice"}""")).andExpect(status().isCreated());
        String outlier = JsonPath.read(
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/batches/{b}", "B-UV")).andReturn().getResponse().getContentAsString(),
                "$.outliers[0].outlierKey");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<Integer>> statuses = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/outliers/{o}/reviews", outlier).header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"ACCEPT\",\"reason\":\"concurrent\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : statuses) {
            int status = f.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 409) {
                conflict++;
            } else {
                throw new AssertionError("非预期状态码: " + status);
            }
        }
        pool.shutdown();
        assertEquals(1, ok, "同一复核人并发复核必须恰好一条落盘");
        assertEquals(threads - 1, conflict);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outlier_review", Integer.class));
    }
}
