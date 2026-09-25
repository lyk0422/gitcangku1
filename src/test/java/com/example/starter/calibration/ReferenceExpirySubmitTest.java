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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标准器证书版本与测量时态测试：
 * 证书版本/补偿系数/不确定度版本的创建与快照固化、端点到期即无效、
 * referenceKey 同键重放、失败不占键、并发同键提交幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReferenceExpirySubmitTest {

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

    private String certJson(String instrument, String from, String to, String a, String b,
                            String certVersion, String comp, String uncertaintyVersion,
                            boolean singleBatchOnly) {
        return """
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s",
                 "certVersion":"%s","compensationCoeff":"%s","uncertaintyVersion":"%s",
                 "singleBatchOnly":%s}
                """.formatted(instrument, from, to, a, b, certVersion, comp, uncertaintyVersion,
                singleBatchOnly);
    }

    private String measurementJson(String key, String referenceKey, String instrument, String at,
                                   String reading, String lower, String upper, String by) {
        String rk = referenceKey == null ? "" : "\"referenceKey\":\"%s\",".formatted(referenceKey);
        return """
                {"measurementKey":"%s",%s"instrumentId":"%s","measuredAt":"%s","reading":"%s",
                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                """.formatted(key, rk, instrument, at, reading, lower, upper, by);
    }

    @Test
    void certificateCreatedWithVersionMetadataAndDefaults() throws Exception {
        // 显式元数据
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-V", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "1.5", "0.25", "CV-3", "0.02", "UV-9", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certVersion").value("CV-3"))
                .andExpect(jsonPath("$.compensationCoeff").value("0.02"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("UV-9"))
                .andExpect(jsonPath("$.singleBatchOnly").value(true))
                .andExpect(jsonPath("$.boundBatchId").doesNotExist());

        // 缺省元数据：certVersion/uncertaintyVersion 默认 "1"，补偿系数默认 0，非单批次
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-D","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certVersion").value("1"))
                .andExpect(jsonPath("$.compensationCoeff").value("0"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("1"))
                .andExpect(jsonPath("$.singleBatchOnly").value(false));
    }

    @Test
    void submitSnapshotsCertificateMetadataAndComputesUncertainty() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "1.5", "0.5", "CV-3", "0.02", "UV-9", false)))
                .andExpect(status().isCreated());

        // computed = 1.5×10+0.5 = 15.5；uncertainty = |0.02×10| = 0.2
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-1", null, "INS-1", "2026-06-01T00:00:00Z",
                                "10", "0", "100", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("15.5"))
                .andExpect(jsonPath("$.uncertainty").value("0.2"))
                .andExpect(jsonPath("$.certVersion").value("CV-3"))
                .andExpect(jsonPath("$.compensationCoeff").value("0.02"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("UV-9"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void endpointExpiryIsInvalidAtValidTo() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z",
                                "1", "0", "CV-1", "0", "UV-1", false)))
                .andExpect(status().isCreated());
        // 终点前一刻有效
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-2", null, "INS-1", "2026-06-30T23:59:59Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isCreated());
        // 恰好终点：端点到期即无效 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-3", null, "INS-1", "2026-07-01T00:00:00Z",
                                "1", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
    }

    @Test
    void referenceKeyReplayReturnsSameResultAndConflictOnDifferentInput() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "1", "0", "CV-1", "0", "UV-1", false)))
                .andExpect(status().isCreated());

        String body = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-4", "RK-1", "INS-1", "2026-06-01T00:00:00Z",
                                "5", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceKey").value("RK-1"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        // 同键同输入重放 → 200，返回同一记录，不产生新行
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-4", "RK-1", "INS-1", "2026-06-01T00:00:00Z",
                                "5", "0", "9", "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE reference_key = 'RK-1'", Integer.class);
        assertEquals(1, count);

        // 同键不同输入 → 409 REFERENCE_KEY_CONFLICT
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-5", "RK-1", "INS-1", "2026-06-01T00:00:00Z",
                                "6", "0", "9", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCE_KEY_CONFLICT"));
    }

    @Test
    void failedSubmitDoesNotOccupyReferenceKey() throws Exception {
        // 无有效证书时提交失败 → 不占键
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-6", "RK-F", "INS-1", "2026-06-01T00:00:00Z",
                                "5", "0", "9", "alice")))
                .andExpect(status().isUnprocessableEntity());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE reference_key = 'RK-F'", Integer.class);
        assertEquals(0, count);

        // 补齐证书后同键可成功提交
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "1", "0", "CV-1", "0", "UV-1", false)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-6", "RK-F", "INS-1", "2026-06-01T00:00:00Z",
                                "5", "0", "9", "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referenceKey").value("RK-F"));
    }

    @Test
    void concurrentSameReferenceKeyExactlyOneCreates() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certJson("INS-C", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "1", "0", "CV-1", "0", "UV-1", false)))
                .andExpect(status().isCreated());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                                .content(measurementJson("E-C", "RK-C", "INS-C",
                                        "2026-06-01T00:00:00Z", "5", "0", "9", "alice")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 200) {
                replayed.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "并发同键提交必须恰好一条创建");
        assertEquals(threads - 1, replayed.get(), "其余必须为同键重放");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE reference_key = 'RK-C'", Integer.class);
        assertEquals(1, count);
    }
}
