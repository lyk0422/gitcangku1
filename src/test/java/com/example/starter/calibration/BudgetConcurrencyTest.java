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
 * 1. 并发分量修改（同一期望版本）恰好一个成功，其余 410；
 * 2. 分量修改与放行并发时按事务提交顺序裁决，结果与最终数据一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_revision_component");
        jdbc.update("DELETE FROM budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM instrument_threshold");
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

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, by)))
                .andExpect(status().isCreated());
    }

    private void setThreshold(String instrument, String requestId, String threshold) throws Exception {
        mvc.perform(put("/api/instruments/{id}/threshold", instrument)
                        .header("X-Actor-Id", "setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"threshold\":\"" + threshold + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentComponentUpdatesExactlyOneWins() throws Exception {
        createCert("INS-C1");
        submit("C-1", "INS-C1", "alice");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/components", "C-1")
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"cc-%d","expectedVersion":1,"components":[
                                          {"name":"a","value":"0.%d"}]}
                                        """.formatted(index, index + 1)))
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
        assertEquals(1, ok.get(), "并发分量修改必须恰好一个成功");
        assertEquals(threads - 1, gone.get(), "其余必须因版本过期返回 410");

        Map<String, Object> measurement = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'C-1'");
        assertEquals(2, ((Number) measurement.get("version")).intValue(), "版本只推进一次");
        Integer components = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component", Integer.class);
        assertEquals(1, components, "最终只有胜者的一个分量");
        Integer revisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_revision", Integer.class);
        assertEquals(1, revisions, "只固化一条修订历史");
    }

    @Test
    void concurrentReleaseAndComponentUpdateFollowCommitOrder() throws Exception {
        // 重复多轮：任意调度下结果必须与提交顺序一致
        for (int round = 0; round < 10; round++) {
            final int r = round;
            String instrument = "INS-C2-" + r;
            String key = "C-2-" + r;
            createCert(instrument);
            // 阈值 0.45：空预算可放行；修改为 0.3/0.4 后合成 0.5 超限
            setThreshold(instrument, "th-c2-" + r, "0.45");
            submit(key, instrument, "alice");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> updateFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(put("/api/measurements/{key}/components", key)
                                .header("X-Actor-Id", "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"requestId":"cu-%d","expectedVersion":1,"components":[
                                          {"name":"a","value":"0.3"},{"name":"b","value":"0.4"}]}
                                        """.formatted(r)))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"" + key + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int updateStatus = updateFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", key);
            Integer componentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM uncertainty_component c "
                            + "JOIN measurement m ON m.id = c.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, key);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record r "
                            + "JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.measurement_key = ?", Integer.class, key);

            if (releaseStatus == 200) {
                // 放行先提交：分量修改必须 409（已固化），分量保持为空
                assertEquals(409, updateStatus, "放行后分量修改必须被拒绝");
                assertEquals("RELEASED", measurement.get("status"));
                assertEquals(0, componentCount);
                assertEquals(1, releaseCount);
            } else {
                // 分量修改先提交：放行必须 409（合成 0.5 超阈值 0.45），测量保持待放行
                assertEquals(409, releaseStatus);
                assertEquals(200, updateStatus, "分量修改必须先成功");
                assertEquals("PENDING", measurement.get("status"));
                assertEquals(2, componentCount);
                assertEquals(0, releaseCount);
                assertEquals(2, ((Number) measurement.get("version")).intValue());
            }
            assertTrue(updateStatus == 200 || updateStatus == 409);
        }
    }
}
