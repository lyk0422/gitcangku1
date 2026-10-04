package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 频道滚动音频能量预算端到端测试：恰好 300 合法、超限 422 整份不发布、滚动窗口不按整分钟桶、
 * 空档与未登记素材按 0、密度登记不可修改与幂等、查询证据与并发边界。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束与事务回滚由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnergyBudgetApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_1000 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_100030 = "2026-09-22T10:00:30.000+08:00";
    private static final String T_1001 = "2026-09-22T10:01:00.000+08:00";
    private static final String T_100130 = "2026-09-22T10:01:30.000+08:00";
    private static final String T_1100 = "2026-09-22T11:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：密度 5 × 60 秒 = 300，恰好合法 ----------

    @Test
    void exactlyBudgetPublishesAndQueryShowsMaxWindowEvidence() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "dense5", 60000);
        registerDensity(asset, "req-" + p + "-reg", "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset))
                .andExpect(jsonPath("$.energyDensity").value(5.0));
        createGrant(channel, asset);
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_1000, T_1001));
        publish(channel, "req-" + p + "-pub", 1, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 查询：最大窗口能量恰为 300，窗口 60 秒，贡献来自该片段完整 60 秒；说明紧急插播例外
        energyBudget(channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.publicationId").isNumber())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.budgetLimit").value(300))
                .andExpect(jsonPath("$.maxWindow.energy").value(300.0))
                .andExpect(jsonPath("$.maxWindow.contributions[0].segmentId").value(p + "s1"))
                .andExpect(jsonPath("$.maxWindow.contributions[0].assetId").value(asset))
                .andExpect(jsonPath("$.maxWindow.contributions[0].energyDensity").value(5.0))
                .andExpect(jsonPath("$.maxWindow.contributions[0].overlapMs").value(60000))
                .andExpect(jsonPath("$.maxWindow.contributions[0].energy").value(300.0))
                .andExpect(jsonPath("$.note").value(containsString("紧急插播")));
    }

    // ---------- 拒绝：密度 5.001 × 60 秒 = 300.06 > 300，整份不发布 ----------

    @Test
    void aboveBudgetRejectsWholePublicationWithoutNewVersionAndFreesRequestId() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "dense5001", 60000);
        registerDensity(asset, "req-" + p + "-reg", "5.001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(5.001));
        createGrant(channel, asset);
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_1000, T_1001));

        // 422：滚动窗口能量 300.06 超限，整份不发布
        publish(channel, "req-" + p + "-pub", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ENERGY_BUDGET_EXCEEDED"));
        // 无新发布版本：当日仍无已发布编排
        energyBudget(channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").doesNotExist())
                .andExpect(jsonPath("$.maxWindow").doesNotExist());

        // 失败不占键：改草稿为 30 秒（5.001 × 30 = 150.03）后用同一 requestId 发布成功
        draft(channel, "req-" + p + "-d2", 1,
                segment(p + "s2", asset, T_1000, T_100030));
        publish(channel, "req-" + p + "-pub", 2, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
        energyBudget(channel)
                .andExpect(jsonPath("$.maxWindow.energy").value(150.03));
    }

    // ---------- 滚动窗口：不按整分钟桶，错位拼接超限 ----------

    @Test
    void rollingWindowNotAlignedToMinuteBuckets() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "dense6", 60000);
        registerDensity(asset, "req-" + p + "-reg", "6").andExpect(status().isOk());
        createGrant(channel, asset);
        // 整分钟桶各 30s × 6 = 180 不超 300，但滚动窗口 [10:00:30, 10:01:30) 合计 360
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_100030, T_1001),
                segment(p + "s2", asset, T_1001, T_100130));
        publish(channel, "req-" + p + "-pub", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ENERGY_BUDGET_EXCEEDED"));
        energyBudget(channel)
                .andExpect(jsonPath("$.publicationId").doesNotExist());
    }

    // ---------- 空档按 0：跨空档窗口只计实际播放 ----------

    @Test
    void gapsCountAsZeroEnergy() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "dense10", 30000);
        registerDensity(asset, "req-" + p + "-reg", "10").andExpect(status().isOk());
        createGrant(channel, asset);
        // 两段 30s × 10 = 300，中间 30 秒空档：任意 60 秒窗口最多 300
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_1000, T_100030),
                segment(p + "s2", asset, T_1001, T_100130));
        publish(channel, "req-" + p + "-pub", 1, 0)
                .andExpect(status().isOk());
        energyBudget(channel)
                .andExpect(jsonPath("$.maxWindow.energy").value(300.0));
    }

    // ---------- 未登记密度的旧素材按 0 ----------

    @Test
    void unregisteredAssetDensityCountsAsZero() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "plain", 3600000);
        createGrant(channel, asset);
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_1000, T_1100));
        publish(channel, "req-" + p + "-pub", 1, 0)
                .andExpect(status().isOk());
        energyBudget(channel)
                .andExpect(jsonPath("$.maxWindow.energy").value(0.0))
                .andExpect(jsonPath("$.maxWindow.contributions[0].energyDensity").value(0.0));
    }
    // ---------- 能量密度登记：不可修改、幂等、失败不占键、参数校验 ----------

    @Test
    void registerEnergyDensityRulesAndIdempotency() throws Exception {
        String p = prefix();
        String asset = newAsset(p, "reg", 60000);

        // 主流程：登记成功
        registerDensity(asset, "req-" + p + "-r1", "7.25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(7.25));
        // 幂等重放：同 requestId 同参数返回首次完整响应
        registerDensity(asset, "req-" + p + "-r1", "7.25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(7.25));
        // 同 requestId 异参：409
        registerDensity(asset, "req-" + p + "-r1", "7.5")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 登记后不可修改：新 requestId 再登记 409
        registerDensity(asset, "req-" + p + "-r2", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ENERGY_DENSITY_ALREADY_REGISTERED"));

        // 失败不占键：素材不存在 404 后，同 requestId 可成功
        String late = p + "late";
        registerDensity(late, "req-" + p + "-r3", "2")
                .andExpect(status().isNotFound());
        newAsset(p, "late", 1000);
        registerDensity(late, "req-" + p + "-r3", "2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(2.0));

        // 400：超出 0~10、超过 3 位小数、负数
        registerDensity(asset, "req-" + p + "-b1", "10.001")
                .andExpect(status().isBadRequest());
        registerDensity(asset, "req-" + p + "-b2", "1.0001")
                .andExpect(status().isBadRequest());
        registerDensity(asset, "req-" + p + "-b3", "-0.5")
                .andExpect(status().isBadRequest());
        // 边界：0 与 10 合法
        String zero = newAsset(p, "zero", 1000);
        registerDensity(zero, "req-" + p + "-z", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(0.0));
        String ten = newAsset(p, "ten", 1000);
        registerDensity(ten, "req-" + p + "-t", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(10.0));
    }

    // ---------- 查询分支：无发布编排、频道不存在 ----------

    @Test
    void energyBudgetQueryBranches() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        // 无已发布编排：200，maxWindow 为空，仍说明紧急插播例外
        energyBudget(channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.publicationId").doesNotExist())
                .andExpect(jsonPath("$.maxWindow").doesNotExist())
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.budgetLimit").value(300))
                .andExpect(jsonPath("$.note").value(containsString("紧急插播")));
        // 404：频道不存在
        energyBudget(p + "ghost")
                .andExpect(status().isNotFound());
        // 400：业务日格式非法
        mvc.perform(get("/api/channels/" + channel + "/energy-budget")
                        .param("businessDay", "2026-13-40"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 并发：同素材登记仅一方成功；同 requestId 并发重放一致 ----------

    @Test
    void concurrentRegisterSameAssetOnlyOneWins() throws Exception {
        String p = prefix();
        String asset = newAsset(p, "race", 60000);

        List<Integer> statuses = runConcurrently(2, i -> registerDensity(
                asset, "req-" + p + "-c" + i, "3").andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 同 requestId 并发：均返回首次响应
        String shared = newAsset(p, "race2", 60000);
        List<Integer> replayed = runConcurrently(2, i -> registerDensity(
                shared, "req-" + p + "-shared", "4").andReturn().getResponse().getStatus());
        assertThat(replayed).containsExactly(200, 200);
        registerDensity(shared, "req-" + p + "-check", "4")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ENERGY_DENSITY_ALREADY_REGISTERED"));
    }

    // ---------- 并发：同一草稿发布仅一方成功（预算检查路径下） ----------

    @Test
    void concurrentPublishWithBudgetCheckOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String asset = newAsset(p, "dense5", 60000);
        registerDensity(asset, "req-" + p + "-reg", "5").andExpect(status().isOk());
        createGrant(channel, asset);
        draft(channel, "req-" + p + "-d1", 0,
                segment(p + "s1", asset, T_1000, T_1001));

        List<Integer> statuses = runConcurrently(2, i -> publish(
                channel, "req-" + p + "-pc" + i, 1, 0)
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        energyBudget(channel)
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.maxWindow.energy").value(300.0));
    }

    // ---------- 测试辅助 ----------

    /** 创建保底素材与频道，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        return p + "ch";
    }

    /** 创建普通素材，返回素材 ID。 */
    private String newAsset(String p, String suffix, long durationMs) throws Exception {
        String id = p + suffix;
        postJson("/api/assets", Map.of("id", id, "durationMs", durationMs))
                .andExpect(status().isOk());
        return id;
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions registerDensity(String asset, String requestId, String density)
            throws Exception {
        return postJson("/api/assets/" + asset + "/energy-density",
                Map.of("requestId", requestId, "energyDensity", new BigDecimal(density)));
    }

    private ResultActions draft(String channel, String requestId, long expectedVersion,
                                Map<String, Object>... segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segments));
        return putJson("/api/channels/" + channel + "/drafts/" + DAY, body);
    }

    private ResultActions publish(String channel, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions energyBudget(String channel) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/energy-budget")
                .param("businessDay", DAY));
    }

    private static Map<String, Object> segment(String id, String assetId,
                                               String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingSupplier {
        Integer get(int index) throws Exception;
    }

    private static List<Integer> runConcurrently(int threads, ThrowingSupplier action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return action.get(index);
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}