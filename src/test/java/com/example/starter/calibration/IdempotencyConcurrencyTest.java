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
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 幂等并发测试：多线程携带相同 requestId 与相同参数并发提交，
 * 必须全部得到首次完整响应（重放），业务记录与幂等记录各一条，不暴露唯一键错误。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    @Test
    void concurrentSameRequestIdAllReplayFirstResponse() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        String json = """
                {"measurementKey":"M-C1","instrumentId":"INS-C","measuredAt":"2026-06-01T00:00:00Z",
                 "reading":"1","lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                 "requestId":"REQ-CONCURRENT"}""";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements")
                                .contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn();
            }));
        }
        start.countDown();
        Set<String> bodies = new HashSet<>();
        Set<Integer> statuses = new HashSet<>();
        for (Future<MvcResult> future : results) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            statuses.add(result.getResponse().getStatus());
            bodies.add(result.getResponse().getContentAsString());
        }
        pool.shutdown();
        assertEquals(Set.of(201), statuses, "并发同键同参必须全部返回首次的 201，不暴露唯一键错误");
        assertEquals(1, bodies.size(), "所有并发响应必须与首次完整响应一致");
        Integer measurementCount = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(1, measurementCount, "业务记录必须恰好一条");
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idemCount, "幂等记录必须恰好一条");
    }
}
