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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境补偿并发与幂等边界测试（真实 H2 行锁与事务）：
 * 1. 同一 requestId 并发首放：恰好写入一条快照与一条放行历史，全部得到同一批次结果；
 * 2. 系数更新与放行并发：放行结果只引用一个完整版本，结果与该版本系数自洽；
 * 3. 不同 requestId 并发放行同一测量：恰好一条成功，其余 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class EnvironmentConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControllableBusinessClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM coefficient_version");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        clock.setTime(FixedClockTestConfig.INITIAL_TIME);
    }

    private void setupInstrument(String instrument, String tempCoeff, String humCoeff) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0",
                                 "tempMin":"20","tempMax":"25","humidityMin":"40","humidityMax":"60"}
                                """.formatted(instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","effectiveFrom":"2026-01-01T00:00:00Z",
                                 "refTemperature":"20","refHumidity":"50",
                                 "temperatureCoeff":"%s","humidityCoeff":"%s"}
                                """.formatted(instrument, tempCoeff, humCoeff)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"10",
                                 "lowerLimit":"0","upperLimit":"20","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private String envReleaseJson(String requestId, String key, String temperature, String humidity) {
        return "{\"requestId\":\"%s\",\"items\":[{\"measurementKey\":\"%s\",\"temperature\":\"%s\",\"humidity\":\"%s\"}]}"
                .formatted(requestId, key, temperature, humidity);
    }

    @Test
    void concurrentSameRequestIdWritesExactlyOneSnapshotAndHistory() throws Exception {
        setupInstrument("INS-C1", "0.01", "0.001");
        submit("M-C1", "INS-C1");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/measurements/environment-release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(envReleaseJson("RQ-C1", "M-C1", "25", "55")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        for (Future<Integer> future : statuses) {
            assertEquals(200, future.get(30, TimeUnit.SECONDS), "同 requestId 并发重放必须全部成功");
            ok.incrementAndGet();
        }
        pool.shutdown();
        assertEquals(threads, ok.get());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_request", Integer.class),
                "只允许一条请求快照");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class),
                "只允许一条放行历史");

        // 所有响应返回同一个 batchId（首次计算快照）
        var snapshots = jdbc.queryForList("SELECT batch_id FROM release_request");
        var records = jdbc.queryForList("SELECT batch_id FROM release_record");
        assertEquals(1, snapshots.stream().map(r -> r.get("batch_id")).distinct().count());
        assertEquals(snapshots.get(0).get("batch_id"), records.get(0).get("batch_id"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-C1'");
        assertEquals("RELEASED", row.get("status"));
        assertEquals("10.0550", row.get("final_value").toString());
    }

    @Test
    void coefficientUpdateConcurrentWithReleaseReferencesOneCompleteVersion() throws Exception {
        // 多轮调度：每轮并发执行“系数更新（v2 自 2026-03-01 生效）”与“环境放行”，
        // 校验放行结果恰好引用一个版本，且补偿值/最终值与所引用版本的系数完全自洽。
        for (int round = 0; round < 10; round++) {
            final int r = round;
            String instrument = "INS-U" + r;
            setupInstrument(instrument, "0.01", "0.001");
            submit("M-U" + r, instrument);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> updateFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"instrumentId":"%s","effectiveFrom":"2026-03-01T00:00:00Z",
                                         "refTemperature":"20","refHumidity":"50",
                                         "temperatureCoeff":"0.02","humidityCoeff":"0.002"}
                                        """.formatted(instrument)))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/measurements/environment-release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(envReleaseJson("RQ-U" + r, "M-U" + r, "25", "55")))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(201, updateFuture.get(30, TimeUnit.SECONDS));
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, releaseStatus, "仪器锁互斥下放行不应读到多版本中间态");

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-U" + r);
            long versionId = ((Number) measurement.get("coeff_version_id")).longValue();
            List<Long> versionIds = jdbc.queryForList(
                    "SELECT id FROM coefficient_version WHERE instrument_id = ? ORDER BY id",
                    Long.class, instrument);
            assertEquals(2, versionIds.size(), "该仪器应恰好存在两个版本（旧版本被闭合）");
            assertTrue(versionIds.contains(versionId), "只能引用 v1 或 v2 中的一个完整版本");

            Map<String, Object> version = jdbc.queryForMap(
                    "SELECT * FROM coefficient_version WHERE id = ?", versionId);
            // 用所引用版本重算补偿与最终值，必须与固化结果逐位一致
            java.math.BigDecimal temperature = new java.math.BigDecimal(measurement.get("temperature").toString());
            java.math.BigDecimal humidity = new java.math.BigDecimal(measurement.get("humidity").toString());
            java.math.BigDecimal refT = new java.math.BigDecimal(version.get("ref_temperature").toString());
            java.math.BigDecimal refH = new java.math.BigDecimal(version.get("ref_humidity").toString());
            java.math.BigDecimal tCoeff = new java.math.BigDecimal(version.get("temperature_coeff").toString());
            java.math.BigDecimal hCoeff = new java.math.BigDecimal(version.get("humidity_coeff").toString());
            java.math.BigDecimal compensation = temperature.subtract(refT).multiply(tCoeff)
                    .add(humidity.subtract(refH).multiply(hCoeff));
            java.math.BigDecimal expectedFinal =
                    new java.math.BigDecimal(measurement.get("computed_value").toString())
                            .add(compensation).setScale(4, java.math.RoundingMode.HALF_UP);
            assertEquals(0, compensation.compareTo(
                    new java.math.BigDecimal(measurement.get("compensation_value").toString())));
            assertEquals(expectedFinal.toPlainString(), measurement.get("final_value").toString());
        }
    }

    @Test
    void concurrentDifferentRequestIdsSameMeasurementExactlyOneWins() throws Exception {
        setupInstrument("INS-C2", "0.01", "0.001");
        submit("M-C2", "INS-C2");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/measurements/environment-release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(envReleaseJson("RQ-C2-" + index, "M-C2", "25", "55")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : statuses) {
            int status = future.get(30, TimeUnit.SECONDS);
            assertTrue(status == 200 || status == 409);
            if (status == 200) {
                ok.incrementAndGet();
            } else {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "不同 requestId 并发放行同一测量必须恰好一条成功");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_request WHERE request_id LIKE 'RQ-C2-%'", Integer.class));
    }
}
