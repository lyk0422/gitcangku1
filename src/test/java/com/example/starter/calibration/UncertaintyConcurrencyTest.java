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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不确定度预算并发边界测试：
 * 1. 并发以同一测量版本批量修改分量时恰好一个成功，其余 410；
 * 2. 分量修改与放行并发时按事务提交顺序裁决，放行读取的预算一致（不出现半成品）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UncertaintyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM uncertainty_budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_threshold");
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

    private void setThreshold(String instrument, String maxUncertainty) throws Exception {
        mvc.perform(put("/api/instruments/{id}/uncertainty-threshold", instrument)
                        .header("X-Actor-Id", "metrology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + java.util.UUID.randomUUID()
                                + "\",\"maxUncertainty\":\"" + maxUncertainty + "\"}"))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentComponentModificationWithSameVersionExactlyOneWins() throws Exception {
        createCert("INS-C");
        setThreshold("INS-C", "1000");
        submit("M-C", "INS-C");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/uncertainty-components", "M-C")
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"req-c-%d","measurementVersion":1,
                                         "components":[{"name":"comp-%d","value":"0.1"}]}
                                        """.formatted(index, index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 410) {
                gone.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发同版本修改必须恰好一个成功");
        assertEquals(threads - 1, gone.get(), "其余必须因版本过期返回 410");

        // 最终状态：版本递增一次，恰好一条修订历史，分量完整来自同一次修改
        Map<String, Object> measurement = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M-C'");
        assertEquals(2, ((Number) measurement.get("measurement_version")).intValue());
        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget_revision", Integer.class);
        assertEquals(1, revisionCount);
        Integer componentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component", Integer.class);
        assertEquals(1, componentCount);
    }

    @Test
    void concurrentModifyAndReleaseFollowCommitOrder() throws Exception {
        // 重复多轮，验证任意调度下放行读取的预算一致，且修改与放行按提交顺序裁决
        for (int round = 0; round < 10; round++) {
            final int r = round;
            createCert("INS-X" + r);
            setThreshold("INS-X" + r, "1000");
            submit("M-X" + r, "INS-X" + r);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> modifyFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/uncertainty-components", "M-X" + r)
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"req-x-%d","measurementVersion":1,
                                         "components":[{"name":"comp-x","value":"0.5"}]}
                                        """.formatted(r)))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"M-X" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int modifyStatus = modifyFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-X" + r);
            long measurementId = ((Number) measurement.get("id")).longValue();
            int finalVersion = ((Number) measurement.get("measurement_version")).intValue();
            Integer componentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM uncertainty_component WHERE measurement_id = ?",
                    Integer.class, measurementId);
            List<Map<String, Object>> releaseRows = jdbc.queryForList(
                    "SELECT * FROM release_record WHERE measurement_id = ?", measurementId);

            if (modifyStatus == 200) {
                // 修改先提交：版本变为 2，分量完整写入
                assertEquals(2, finalVersion);
                assertEquals(1, componentCount);
            } else {
                // 放行先提交：修改必须 409（预算已固化），分量未被写入
                assertEquals(409, modifyStatus);
                assertEquals(1, finalVersion);
                assertEquals(0, componentCount);
            }

            assertEquals(200, releaseStatus, "阈值充足时放行应成功");
            assertEquals(1, releaseRows.size());
            int snapshotBudgetVersion =
                    ((Number) releaseRows.get(0).get("budget_version")).intValue();
            String snapshotComponents = (String) releaseRows.get(0).get("components_snapshot");
            if (modifyStatus == 200) {
                // 放行读取到修改后的一致预算：快照版本与分量完整
                assertEquals(2, snapshotBudgetVersion);
                assertTrue(snapshotComponents.contains("comp-x"), "放行快照必须包含完整分量");
            } else {
                assertEquals(1, snapshotBudgetVersion);
                assertEquals("[]", snapshotComponents);
            }
            assertEquals("RELEASED", measurement.get("status"));
        }
    }
}
