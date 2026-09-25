package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境补偿并发与幂等边界（真实 H2）：
 * 1. 同型号同输入并发更新系数只产生一个版本（calcKey 重放）；不同输入并发各产生版本；
 * 2. 同测量同环境并发重算只产生一个新版本；
 * 3. 重算环境超区间失败不占 calcKey，修订后可成功；
 * 4. 补偿门禁并发放行同一批恰好一批成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompensationConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM calc_log");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM reject_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM compensation_profile");
        jdbc.update("DELETE FROM compensation_model_lock");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private String profileJson(String model, String kT) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("instrumentModel", model);
        body.put("tempCoeff", kT);
        body.put("humidityCoeff", "0.001");
        body.put("tempMin", "10");
        body.put("tempMax", "40");
        body.put("humidityMin", "20");
        body.put("humidityMax", "80");
        try {
            return JSON.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void concurrentSameProfileInputCreatesExactlyOneVersion() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                int status = mvc.perform(post("/api/compensation-profiles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profileJson("MODEL-C", "0.01")))
                        .andReturn().getResponse().getStatus();
                return status;
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            int status = f.get(30, TimeUnit.SECONDS);
            assertEquals(201, status, "首次与重放都返回 201");
        }
        pool.shutdown();

        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_profile WHERE instrument_model='MODEL-C'", Integer.class);
        assertEquals(1, versions, "同输入并发更新必须只产生一个版本");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_profile WHERE instrument_model='MODEL-C' AND active=TRUE",
                Integer.class);
        assertEquals(1, active);
    }

    @Test
    void concurrentDifferentProfileInputsEachCreateVersion() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                // 不同系数 → 不同 calcKey，各自成为一个版本
                return mvc.perform(post("/api/compensation-profiles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profileJson("MODEL-D", "0.0" + (index + 1))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_profile WHERE instrument_model='MODEL-D'", Integer.class);
        assertEquals(threads, versions, "不同输入并发更新各产生一个版本，版本号连续");
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compensation_profile WHERE instrument_model='MODEL-D' AND active=TRUE",
                Integer.class);
        assertEquals(1, active, "始终恰好一个生效版本");
    }

    @Test
    void concurrentSameRecalculateCreatesExactlyOneNewVersion() throws Exception {
        setupMeasurement("RC-C", "10");
        // 先有一个生效系数版本 v1；更新出 v2 后并发重算
        createProfileViaMvc("MODEL-C", "0.02");

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                Map<String, String> body = new LinkedHashMap<>();
                body.put("measurementKey", "RC-C");
                body.put("instrumentModel", "MODEL-C");
                body.put("temperature", "25");
                body.put("humidity", "50");
                body.put("uncertainty", "0.05");
                return mvc.perform(post("/api/measurements/recalculate")
                                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertEquals(200, f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Long measurementId = jdbc.queryForObject(
                "SELECT id FROM measurement WHERE measurement_key='RC-C'", Long.class);
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_version WHERE measurement_id=?", Integer.class, measurementId);
        assertEquals(2, versionCount, "同环境并发重算只产生 v2 一个新版本（calcKey 重放）");
    }

    @Test
    void failedOutOfRangeRecalculateDoesNotOccupyCalcKey() throws Exception {
        setupMeasurement("RC-F", "10");
        createProfileViaMvc("MODEL-C", "0.02");

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("measurementKey", "RC-F");
        bad.put("instrumentModel", "MODEL-C");
        bad.put("temperature", "99"); // 超出 tempMax=40
        bad.put("humidity", "50");
        mvc.perform(post("/api/measurements/recalculate")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(bad)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(result -> assertEquals("ENVIRONMENT_OUT_OF_RANGE",
                        com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.code")));

        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM calc_log WHERE operation='RECALCULATE'", Integer.class),
                "失败重算不占 calcKey");
        Long measurementId = jdbc.queryForObject(
                "SELECT id FROM measurement WHERE measurement_key='RC-F'", Long.class);
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version WHERE measurement_id=?",
                        Integer.class, measurementId),
                "失败重算不产生新版本");

        // 修订为区间内环境后可成功重算
        Map<String, String> good = new LinkedHashMap<>();
        good.put("measurementKey", "RC-F");
        good.put("instrumentModel", "MODEL-C");
        good.put("temperature", "25");
        good.put("humidity", "50");
        mvc.perform(post("/api/measurements/recalculate")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(good)))
                .andExpect(status().isOk());
        assertEquals(2,
                jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version WHERE measurement_id=?",
                        Integer.class, measurementId));
    }

    @Test
    void concurrentGateReleaseSameBatchExactlyOneWins() throws Exception {
        setupMeasurement("GR-C", "10");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                Map<String, Object> body = Map.of("keys", List.of("GR-C"), "uncertaintyLimit", "0.1");
                return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (Future<Integer> f : futures) {
            int status = f.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "门禁并发放行同一批必须恰好一批成功");
        assertEquals(threads - 1, rejected.get(), "其余按提交顺序裁决为冲突");
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM release_record r "
                        + "JOIN measurement m ON m.id=r.measurement_id WHERE m.measurement_key='GR-C'",
                        Integer.class));
    }

    private void setupMeasurement(String key, String reading) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        // 先建 v1 系数，提交带环境测量
        createProfileViaMvc("MODEL-C", "0.01");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("measurementKey", key);
        body.put("instrumentId", "INS-1");
        body.put("instrumentModel", "MODEL-C");
        body.put("measuredAt", "2026-06-01T00:00:00Z");
        body.put("reading", reading);
        body.put("lowerLimit", "0");
        body.put("upperLimit", "99");
        body.put("temperature", "25");
        body.put("humidity", "50");
        body.put("uncertainty", "0.05");
        body.put("submittedBy", "alice");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void createProfileViaMvc(String model, String kT) throws Exception {
        mvc.perform(post("/api/compensation-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson(model, kT)))
                .andExpect(status().isCreated());
    }
}
