package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写请求幂等去重测试：同参回首次完整响应、异参 409、失败可复用键、
 * 十进制按值归一、操作者必填、并发同 requestId 唯一键竞争给业务结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DedupApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM drift_reference_archive");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM request_dedup");
    }

    private void createCert() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
    }

    private String measurementJson(String key, String reading, String requestId) {
        return """
                {"measurementKey":"%s","instrumentId":"INS-1",
                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice","requestId":"%s"}
                """.formatted(key, reading, requestId);
    }

    @Test
    void sameParamsReplayFirstFullResponse() throws Exception {
        createCert();
        String json = measurementJson("M-1", "1", "REQ-1");
        String first = mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "同参重放必须返回首次完整响应");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count, "重放不得产生第二条测量");
    }

    @Test
    void decimalParamsComparedByValue() throws Exception {
        createCert();
        String first = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-2", "1.0", "REQ-2")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // 1.00 与 1.0 同值 → 视为同参，重放首次响应
        String second = mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-2", "1.00", "REQ-2")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void differentParamsSameRequestIdReturns409() throws Exception {
        createCert();
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-3", "1", "REQ-3")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-3", "2", "REQ-3")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void failedRequestAllowsKeyReuse() throws Exception {
        // 无证书 → 业务 422 失败，去重记录随事务回滚，requestId 可复用
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-4", "1", "REQ-4")))
                .andExpect(status().isUnprocessableEntity());
        createCert();
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("M-4", "1", "REQ-4")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("M-4"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void operatorRequiredWhenRequestIdPresent() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0",
                                 "requestId":"REQ-5"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentSameRequestIdRacesToSingleBusinessResult() throws Exception {
        createCert();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(measurementJson("M-C", "1", "REQ-C")))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        Set<String> bodies = new HashSet<>();
        for (Future<String> future : futures) {
            String body = future.get(30, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertFalse(body.isBlank(), "竞争方必须拿到业务结果");
            bodies.add(body);
        }
        pool.shutdown();
        assertEquals(1, bodies.size(), "并发同 requestId 同参数必须得到同一首次响应");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, count, "唯一键竞争下只允许写入一条测量");
        Integer dedupCount = jdbc.queryForObject("SELECT COUNT(*) FROM request_dedup", Integer.class);
        assertEquals(1, dedupCount, "只允许一条去重记录");
    }
}
