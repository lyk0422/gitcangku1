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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差分并发测试：并发绑定同一测量恰好一条成功、并发放行同一差分恰好一次成功、
 * 并发同 requestId 同参数全部重放首次响应且不泄露唯一键异常。
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
        jdbc.update("DELETE FROM differential_pair");
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

    private void submit(String key, String reading, String lower, String upper, String by)
            throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private String createBody(String refKey, String dutKey) {
        return """
                {"referenceKey":"%s","dutKey":"%s","lowerLimit":"0","upperLimit":"0.3"}
                """.formatted(refKey, dutKey);
    }

    @Test
    void concurrentCreateSharingMeasurementExactlyOneSucceeds() throws Exception {
        createCert("INS-1");
        submit("REF-S", "9.8", "9", "10", "alice");
        int threads = 6;
        for (int i = 0; i < threads; i++) {
            submit("DUT-S" + i, "10.1", "10", "11", "bob");
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/differentials")
                                .header("X-Actor-Id", "dave")
                                .header("X-Request-Id", "conc-create-" + index)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody("REF-S", "DUT-S" + index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "共享同一测量的并发差分创建必须恰好一条成功");
        assertEquals(threads - 1, conflicts.get());
        Integer diffCount = jdbc.queryForObject("SELECT COUNT(*) FROM differential_pair", Integer.class);
        Integer occupationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM differential_occupation", Integer.class);
        assertEquals(1, diffCount);
        assertEquals(2, occupationCount);
    }

    @Test
    void concurrentReleaseSameDifferentialExactlyOneWins() throws Exception {
        createCert("INS-1");
        submit("REF-R", "9.8", "9", "10", "alice");
        submit("DUT-R", "10.1", "10", "11", "bob");
        String created = mvc.perform(post("/api/differentials")
                        .header("X-Actor-Id", "dave").header("X-Request-Id", "conc-rel-create")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody("REF-R", "DUT-R")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/differentials/{id}/release", id)
                                .header("X-Actor-Id", "carol")
                                .header("X-Request-Id", "conc-rel-" + index))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发放行同一差分必须恰好一次成功");
        assertEquals(threads - 1, conflicts.get());
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount, "两条原测量各产生一条放行历史");
        Integer releasedMeasurements = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE status = 'RELEASED'", Integer.class);
        assertEquals(2, releasedMeasurements);
    }

    @Test
    void concurrentSameRequestIdSameParamsAllReplay() throws Exception {
        createCert("INS-1");
        submit("REF-I", "9.8", "9", "10", "alice");
        submit("DUT-I", "10.1", "10", "11", "bob");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/differentials")
                                .header("X-Actor-Id", "dave")
                                .header("X-Request-Id", "conc-idem")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody("REF-I", "DUT-I")))
                        .andReturn().getResponse().getContentAsString();
            }));
        }
        start.countDown();
        Set<String> bodies = new HashSet<>();
        for (Future<String> future : results) {
            bodies.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, bodies.size(), "同键同参的并发请求必须全部得到首次完整响应");
        Integer diffCount = jdbc.queryForObject("SELECT COUNT(*) FROM differential_pair", Integer.class);
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, diffCount);
        assertEquals(1, idemCount);
    }
}
