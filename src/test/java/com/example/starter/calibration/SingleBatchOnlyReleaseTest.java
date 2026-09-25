package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 放行门禁测试：singleBatchOnly 证书首次引用绑定批次、跨批次引用 422 并返回已绑定批次、
 * 证书过期/撤销整批放行状态不变、并发绑定恰好一批成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SingleBatchOnlyReleaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String from, String to, boolean singleBatchOnly)
            throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"1","b":"0",
                                 "certVersion":"CV-1","compensationCoeff":"0","uncertaintyVersion":"UV-1",
                                 "singleBatchOnly":%s}
                                """.formatted(instrument, from, to, singleBatchOnly)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String measuredAt) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s",
                                 "reading":"1","lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key, instrument, measuredAt)))
                .andExpect(status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        String jsonKeys = "\"" + String.join("\",\"", keys) + "\"";
        return mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + jsonKeys + "]}"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void singleBatchOnlyBindsOnFirstReleaseAndRejectsOtherBatch() throws Exception {
        long certId = createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", true);
        submit("S-1", "INS-1", "2026-06-01T00:00:00Z");
        submit("S-2", "INS-1", "2026-06-01T00:00:00Z");

        // 首次引用：绑定该批次
        String first = release("carol", "S-1");
        String batchId = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");
        mvc.perform(get("/api/certificates/{id}", certId))
                .andExpect(jsonPath("$.boundBatchId").value(batchId));

        // 后续被其他批次引用 → 422 并返回已绑定批次，整批状态不变
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"S-2\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("S-2"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_BOUND_TO_OTHER_BATCH"))
                .andExpect(jsonPath("$.failures[0].boundBatchId").value(batchId));

        mvc.perform(get("/api/measurements/{key}", "S-2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
    }

    @Test
    void nonMarkedCertificateAllowsMultipleBatches() throws Exception {
        createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", false);
        submit("N-1", "INS-1", "2026-06-01T00:00:00Z");
        submit("N-2", "INS-1", "2026-06-01T00:00:00Z");

        release("carol", "N-1");
        // 未标记 singleBatchOnly：不限制跨批次引用
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"N-2\"]}"))
                .andExpect(status().isOk());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
    }

    @Test
    void expiredCertificateBlocksReleaseAndKeepsState() throws Exception {
        // 证书在放行时刻已过期（validTo 早于当前时间）
        createCert("INS-1", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z", false);
        submit("X-1", "INS-1", "2026-03-01T00:00:00Z");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"X-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_EXPIRED"));

        // 整批放行状态不变
        mvc.perform(get("/api/measurements/{key}", "X-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
    }

    @Test
    void concurrentBindingExactlyOneBatchWins() throws Exception {
        long certId = createCert("INS-1", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", true);
        submit("C-1", "INS-1", "2026-06-01T00:00:00Z");
        submit("C-2", "INS-1", "2026-06-01T00:00:00Z");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String key : List.of("C-1", "C-2")) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger unprocessable = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 422) {
                unprocessable.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发绑定同一 singleBatchOnly 证书必须恰好一批成功");
        assertEquals(1, unprocessable.get(), "另一批必须为 422 绑定冲突");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
        String boundBatch = jdbc.queryForObject(
                "SELECT bound_batch_id FROM calibration_certificate WHERE id = ?",
                String.class, certId);
        assertTrue(boundBatch != null && !boundBatch.isBlank(), "证书必须绑定到成功批次");
    }
}
