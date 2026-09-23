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
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失效单幂等、闭包漂移与并发边界测试：
 * requestId 同参重放首次闭包快照、异参 409、失败不占键；invalidationKey 唯一；
 * 创建后血缘或结果状态变化激活整单 409；失效激活与并发放行按提交顺序，失效提交后不得放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvalidationIdempotencyConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM impact_item");
        jdbc.update("DELETE FROM invalidation_confirmation");
        jdbc.update("DELETE FROM invalidation_order");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM standard_version");
        jdbc.update("DELETE FROM lineage_lock");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createStandard(String standardId, String parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"%s","parentStandardId":%s,"validFrom":"2025-01-01T00:00:00Z",
                                 "validTo":"2029-01-01T00:00:00Z","certificateNo":"C-%s"}"""
                                .formatted(standardId, parent, standardId)))
                .andExpect(status().isCreated());
    }

    private void measurement(String key, String standardId, String actor) throws Exception {
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-%s","validFrom":"2025-01-01T00:00:00Z",
                                 "validTo":"2029-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(key)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"INS-%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s",
                                 "standardId":"%s"}""".formatted(key, key, actor, standardId)))
                .andExpect(status().isCreated());
    }

    private MvcResult createOrder(String requestId, String key, String root, String reason) throws Exception {
        return mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","invalidationKey":"%s","rootStandardId":"%s",
                                 "invalidFrom":"2026-01-01T00:00:00Z","expectedVersion":0,"reason":"%s"}"""
                                .formatted(requestId, key, root, reason)))
                .andReturn();
    }

    @Test
    void requestIdReplaySameParamsReturnsFirstSnapshotAndDifferentParamsConflict() throws Exception {
        createStandard("ROOT", null);
        measurement("M1", "ROOT", "alice");

        MvcResult first = createOrder("REQ-1", "INV-1", "ROOT", "first reason");
        assertEquals(201, first.getResponse().getStatus());
        String firstBody = first.getResponse().getContentAsString();

        // 同参重放返回首次闭包快照（与首次响应逐字节一致）
        MvcResult replay = createOrder("REQ-1", "INV-1", "ROOT", "first reason");
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(firstBody, replay.getResponse().getContentAsString(), "重放必须返回首次闭包快照");

        // 异参 409
        MvcResult different = createOrder("REQ-1", "INV-1", "ROOT", "changed reason");
        assertEquals(409, different.getResponse().getStatus());
        assertEquals("REQUEST_ID_CONFLICT", JsonPath.read(different.getResponse().getContentAsString(), "$.code"));

        // invalidationKey 唯一：同 key 不同 requestId 409
        MvcResult keyClash = createOrder("REQ-2", "INV-1", "ROOT", "first reason");
        assertEquals(409, keyClash.getResponse().getStatus());
        assertEquals("DUPLICATE_INVALIDATION_KEY",
                JsonPath.read(keyClash.getResponse().getContentAsString(), "$.code"));

        // 数据库中仅有一条失效单
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM invalidation_order", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void failedValidationDoesNotOccupyKey() throws Exception {
        createStandard("ROOT", null);
        // expectedVersion 错误 → 409，不占键
        MvcResult failed = mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-FAIL","invalidationKey":"INV-FAIL","rootStandardId":"ROOT",
                                 "invalidFrom":"2026-01-01T00:00:00Z","expectedVersion":7,"reason":"x"}"""))
                .andExpect(status().isConflict())
                .andReturn();
        assertEquals("EXPECTED_VERSION_MISMATCH", JsonPath.read(failed.getResponse().getContentAsString(), "$.code"));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM invalidation_order", Integer.class);
        assertEquals(0, count, "失败请求不得占用 requestId 或 invalidationKey");

        // 同一 requestId 与 key 可用正确参数成功创建
        MvcResult retry = createOrder("REQ-FAIL", "INV-FAIL", "ROOT", "x");
        assertEquals(201, retry.getResponse().getStatus());
    }

    @Test
    void closureDriftByNewMeasurementRejectsActivationEntirely() throws Exception {
        createStandard("ROOT", null);
        measurement("M1", "ROOT", "alice");
        createOrder("REQ-D1", "INV-D1", "ROOT", "drift");

        // 创建失效单后新增一条绑定 ROOT 的受影响测量 → 闭包漂移
        measurement("M2", "ROOT", "bob");

        mvc.perform(post("/api/invalidations/INV-D1/confirm").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D1/confirm").header("X-Actor-Id", "qm-2"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D1/activate").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_DRIFT"));

        // 整单未生效：标准器仍 VALID，测量仍 PENDING
        assertEquals("VALID", jdbc.queryForMap("SELECT * FROM standard_version WHERE standard_id = 'ROOT'")
                .get("status"));
        assertEquals("PENDING", jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M1'")
                .get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM impact_item", Integer.class));
    }

    @Test
    void closureDriftByReleasedStatusChangeRejectsActivation() throws Exception {
        createStandard("ROOT", null);
        measurement("M1", "ROOT", "alice");
        createOrder("REQ-D2", "INV-D2", "ROOT", "drift");

        // 创建失效单后放行结果：快照中 PENDING，激活时 RELEASED → 结果状态漂移
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M1\"]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/invalidations/INV-D2/confirm").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D2/confirm").header("X-Actor-Id", "qm-2"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D2/activate").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_DRIFT"));

        // 结果保持 RELEASED，未被部分冻结
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M1'");
        assertEquals("RELEASED", row.get("status"));
        assertEquals("VALID", jdbc.queryForMap("SELECT * FROM standard_version WHERE standard_id = 'ROOT'")
                .get("status"));
    }

    @Test
    void closureDriftByNewLineageRejectsActivation() throws Exception {
        createStandard("ROOT", null);
        measurement("M1", "ROOT", "alice");
        createOrder("REQ-D3", "INV-D3", "ROOT", "drift");

        // 创建失效单后新增血缘子孙 → 标准器闭包漂移
        createStandard("STD-LATE", "ROOT");

        mvc.perform(post("/api/invalidations/INV-D3/confirm").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D3/confirm").header("X-Actor-Id", "qm-2"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-D3/activate").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_DRIFT"));
    }

    @Test
    void activationAndReleaseConcurrentFollowCommitOrder() throws Exception {
        // 多轮重复验证任意调度：失效激活先提交则放行 409；放行先提交则激活 409（状态漂移）
        for (int round = 0; round < 10; round++) {
            int r = round;
            createStandard("ROOT-R" + r, null);
            measurement("M-R" + r, "ROOT-R" + r, "alice");
            createOrder("REQ-C" + r, "INV-C" + r, "ROOT-R" + r, "concurrent");
            mvc.perform(post("/api/invalidations/{key}/confirm", "INV-C" + r)
                            .header("X-Actor-Id", "qm-1"))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/invalidations/{key}/confirm", "INV-C" + r)
                            .header("X-Actor-Id", "qm-2"))
                    .andExpect(status().isOk());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> activateFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/invalidations/{key}/activate", "INV-C" + r)
                                .header("X-Actor-Id", "qm-1"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"M-R" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int activateStatus = activateFuture.get(60, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "M-R" + r);
            String finalStatus = (String) row.get("status");
            if (activateStatus == 200) {
                // 失效先提交：放行必须失败，记录 BLOCKED
                assertEquals(409, releaseStatus, "失效提交后不得再放行受影响结果 (round " + r + ")");
                assertEquals("BLOCKED", finalStatus);
            } else {
                // 放行先提交：激活时闭包状态漂移，整单 409；放行已成功
                assertEquals(409, activateStatus);
                assertEquals(200, releaseStatus);
                assertEquals("RELEASED", finalStatus);
            }
        }
    }

    @Test
    void concurrentActivationOfSameOrderSucceedsAtMostOnce() throws Exception {
        createStandard("ROOT-Z", null);
        measurement("M-Z", "ROOT-Z", "alice");
        createOrder("REQ-Z", "INV-Z", "ROOT-Z", "once");
        mvc.perform(post("/api/invalidations/INV-Z/confirm").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-Z/confirm").header("X-Actor-Id", "qm-2"))
                .andExpect(status().isOk());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/invalidations/INV-Z/activate").header("X-Actor-Id", "qm-1"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(60, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发激活必须恰好一次成功");
        assertEquals(threads - 1, conflict.get());
        // 仅生成一个 impactVersion，测量被整体冻结为 BLOCKED
        Integer distinctVersions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT impact_version) FROM measurement WHERE measurement_key = 'M-Z'",
                Integer.class);
        assertEquals(1, distinctVersions);
        Integer orderVersions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT impact_version) FROM invalidation_order WHERE invalidation_key = 'INV-Z'",
                Integer.class);
        assertEquals(1, orderVersions);
        assertEquals("BLOCKED", jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M-Z'").get("status"));
    }
}
