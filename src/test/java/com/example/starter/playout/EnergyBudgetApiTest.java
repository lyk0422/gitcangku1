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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 频道滚动音频能量预算端到端测试：素材能量密度登记、发布前 60 秒滚动窗口预算检查、
 * 快照冻结证据查询、幂等重放与并发边界。运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnergyBudgetApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 能量密度登记 ----------

    @Test
    void energyDensityRegistrationValidationAndIdempotency() throws Exception {
        String p = prefix();
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());

        // 400：超过 10 / 小数超过 3 位 / 负数 / 缺操作者
        registerDensity(p + "movie", "req-" + p + "-b1", "tester", new BigDecimal("10.001"))
                .andExpect(status().isBadRequest());
        registerDensity(p + "movie", "req-" + p + "-b2", "tester", new BigDecimal("5.0001"))
                .andExpect(status().isBadRequest());
        registerDensity(p + "movie", "req-" + p + "-b3", "tester", new BigDecimal("-0.1"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/assets/" + p + "movie/energy-density")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", "req-" + p + "-b4",
                                "energyDensity", 5))))
                .andExpect(status().isBadRequest());

        // 404：素材不存在；失败不占用 requestId，同键修复参数后成功
        registerDensity(p + "ghost", "req-" + p + "-retry", "tester", new BigDecimal("5"))
                .andExpect(status().isNotFound());
        registerDensity(p + "movie", "req-" + p + "-retry", "tester", new BigDecimal("5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(p + "movie"))
                .andExpect(jsonPath("$.energyDensity").value(5))
                .andExpect(jsonPath("$.registered").value(true));

        // 同 requestId 同参数重放：返回首次完整响应
        registerDensity(p + "movie", "req-" + p + "-retry", "tester", new BigDecimal("5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.energyDensity").value(5));
        // 同 requestId 异参数：409
        registerDensity(p + "movie", "req-" + p + "-retry", "tester", new BigDecimal("6"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 新 requestId 重复登记：409，登记后不可修改
        registerDensity(p + "movie", "req-" + p + "-again", "tester", new BigDecimal("6"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ENERGY_DENSITY_ALREADY_REGISTERED"));
    }
    // ---------- 发布预算边界 ----------

    @Test
    void publishExactlyAtBudgetSucceedsAndQueryShowsEvidence() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("5"))
                .andExpect(status().isOk());

        // 60 秒密度 5：积分恰好 300，合法
        putDraft(channel, "req-" + p + "-d1", 0,
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:01:00.000+08:00"))
                .andExpect(status().isOk());
        MvcResult published = publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 查询：定位最大区间及各片段贡献，明确紧急插播例外
        MvcResult budget = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.budgetLimit").value(300))
                .andExpect(jsonPath("$.maxWindowStart").value("2026-09-22T10:00:00.000+08:00"))
                .andExpect(jsonPath("$.maxWindowEnd").value("2026-09-22T10:01:00.000+08:00"))
                .andExpect(jsonPath("$.emergencyOverridesExcluded").value(true))
                .andExpect(jsonPath("$.note").value("紧急插播不计入本预算"))
                .andExpect(jsonPath("$.contributions[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.contributions[0].assetId").value(p + "movie"))
                .andExpect(jsonPath("$.contributions[0].energyDensity").value(5))
                .andExpect(jsonPath("$.contributions[0].overlapMs").value(60000))
                .andReturn();
        JsonNode body = readJson(budget);
        assertThat(new BigDecimal(body.get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(new BigDecimal("300"));
        assertThat(new BigDecimal(body.get("contributions").get(0).get("energy").asText()))
                .isEqualByComparingTo(new BigDecimal("300"));
    }

    @Test
    void publishAboveBudgetRejectedWithoutNewVersion() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("5.001"))
                .andExpect(status().isOk());

        // 60 秒密度 5.001：积分 300.06 > 300，422 整份不发布
        putDraft(channel, "req-" + p + "-d1", 0,
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:01:00.000+08:00"))
                .andExpect(status().isOk());
        publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ENERGY_BUDGET_EXCEEDED"));

        // 无新发布版本：播出查询仍无已发布编排
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:00:30.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 失败不占 requestId：修复草稿（改用未登记素材，密度按 0）后同键同版本期望发布成功
        postJson("/api/assets", Map.of("id", p + "quiet", "durationMs", 3600000))
                .andExpect(status().isOk());
        createGrant(channel, p + "quiet");
        putDraft(channel, "req-" + p + "-d2", 1,
                segment(p + "seg-2", p + "quiet",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:01:00.000+08:00"))
                .andExpect(status().isOk());
        // expectedPublishedVersion 仍为 0 即成功，证明 422 没有推进发布版本
        publish(channel, "req-" + p + "-p1", 2, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    @Test
    void rollingWindowSpansSegmentsNotMinuteBuckets() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("10"))
                .andExpect(status().isOk());

        // 两个相邻 30 秒片段跨整分钟：整分钟桶各 300 合法，但真实 60 秒窗口为 600，必须拒绝
        putDraft(channel, "req-" + p + "-d1", 0, List.of(
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:30.000+08:00", "2026-09-22T10:01:00.000+08:00"),
                segment(p + "seg-2", p + "movie",
                        "2026-09-22T10:01:00.000+08:00", "2026-09-22T10:01:30.000+08:00")))
                .andExpect(status().isOk());
        publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ENERGY_BUDGET_EXCEEDED"));

        // 同样布局改用密度 5 的素材：跨片段窗口恰好 300，合法；最大窗口起点对齐片段起点（非整分钟）
        postJson("/api/assets", Map.of("id", p + "calm", "durationMs", 3600000))
                .andExpect(status().isOk());
        createGrant(channel, p + "calm");
        registerDensity(p + "calm", "req-" + p + "-reg2", "tester", new BigDecimal("5"))
                .andExpect(status().isOk());
        putDraft(channel, "req-" + p + "-d2", 1, List.of(
                segment(p + "seg-3", p + "calm",
                        "2026-09-22T10:00:30.000+08:00", "2026-09-22T10:01:00.000+08:00"),
                segment(p + "seg-4", p + "calm",
                        "2026-09-22T10:01:00.000+08:00", "2026-09-22T10:01:30.000+08:00")))
                .andExpect(status().isOk());
        MvcResult published = publish(channel, "req-" + p + "-p2", 2, 0)
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();
        MvcResult budget = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxWindowStart").value("2026-09-22T10:00:30.000+08:00"))
                .andExpect(jsonPath("$.maxWindowEnd").value("2026-09-22T10:01:30.000+08:00"))
                .andExpect(jsonPath("$.contributions.length()").value(2))
                .andReturn();
        assertThat(new BigDecimal(readJson(budget).get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(new BigDecimal("300"));
    }

    @Test
    void gapsAndFallbackCountAsZero() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("10"))
                .andExpect(status().isOk());

        // 30 秒密度 10 + 30 秒空档 + 30 秒密度 10：任一 60 秒窗口最多 300，合法
        putDraft(channel, "req-" + p + "-d1", 0, List.of(
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:00:30.000+08:00"),
                segment(p + "seg-2", p + "movie",
                        "2026-09-22T10:01:00.000+08:00", "2026-09-22T10:01:30.000+08:00")))
                .andExpect(status().isOk());
        MvcResult published = publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();
        MvcResult budget = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributions.length()").value(1))
                .andReturn();
        assertThat(new BigDecimal(readJson(budget).get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(new BigDecimal("300"));
    }
    // ---------- 冻结证据与紧急插播例外 ----------

    @Test
    void unregisteredAssetCountsAsZeroAndSnapshotStaysFrozen() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        // 素材未登记密度（按 0）：任意时长都可发布
        putDraft(channel, "req-" + p + "-d1", 0,
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T11:00:00.000+08:00"))
                .andExpect(status().isOk());
        MvcResult published = publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();
        MvcResult before = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributions[0].energyDensity").value(0))
                .andReturn();
        assertThat(new BigDecimal(readJson(before).get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 发布后登记密度：历史快照证据不变（发布时冻结为 0）
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("10"))
                .andExpect(status().isOk());
        MvcResult after = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contributions[0].energyDensity").value(0))
                .andReturn();
        assertThat(new BigDecimal(readJson(after).get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void emergencyOverrideExcludedFromBudgetAndQueryStatesException() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        registerDensity(p + "movie", "req-" + p + "-reg", "tester", new BigDecimal("5"))
                .andExpect(status().isOk());
        putDraft(channel, "req-" + p + "-d1", 0,
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:01:00.000+08:00"))
                .andExpect(status().isOk());
        MvcResult published = publish(channel, "req-" + p + "-p1", 1, 0)
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 紧急插播使用密度 10 的素材，不计入本预算
        postJson("/api/assets", Map.of("id", p + "alert", "durationMs", 600000))
                .andExpect(status().isOk());
        long alertGrant = createGrant(channel, p + "alert");
        registerDensity(p + "alert", "req-" + p + "-reg2", "tester", new BigDecimal("10"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov", "overrideKey", p + "ov-1",
                        "channelId", channel, "assetId", p + "alert", "grantId", alertGrant,
                        "priority", 9,
                        "start", "2026-09-22T10:00:00.000+08:00",
                        "end", "2026-09-22T10:10:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 预算证据仍只反映正常编排（300），查询明确说明紧急插播例外
        MvcResult budget = getJson("/api/publications/" + publicationId + "/energy-budget")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emergencyOverridesExcluded").value(true))
                .andExpect(jsonPath("$.note").value("紧急插播不计入本预算"))
                .andExpect(jsonPath("$.contributions.length()").value(1))
                .andReturn();
        assertThat(new BigDecimal(readJson(budget).get("maxWindowEnergy").asText()))
                .isEqualByComparingTo(new BigDecimal("300"));

        // 404：发布快照不存在
        getJson("/api/publications/999999/energy-budget")
                .andExpect(status().isNotFound());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentRegistrationAndPublishResolveCleanly() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        // 并发登记同一素材（不同 requestId）：恰一个 200，另一个 409，不出现 500
        List<Integer> registrations = runConcurrently(2, i ->
                registerDensity(p + "movie", "req-" + p + "-cr" + i, "tester",
                        new BigDecimal("5"))
                        .andReturn().getResponse().getStatus());
        assertThat(registrations).containsExactlyInAnyOrder(200, 409);

        // 并发相同 requestId 相同参数登记另一素材：都 200 且响应一致（重放首次结果）
        postJson("/api/assets", Map.of("id", p + "song", "durationMs", 1000))
                .andExpect(status().isOk());
        List<String> replays = runConcurrentlyForBody(2, i ->
                registerDensity(p + "song", "req-" + p + "-same", "tester", new BigDecimal("7"))
                        .andReturn().getResponse().getContentAsString());
        assertThat(replays).hasSize(2);
        assertThat(replays.get(0)).isEqualTo(replays.get(1));
        assertThat(readJson(replays.get(0)).get("energyDensity").asText()).isEqualTo("7");

        // 并发发布同一版本：恰一个 200，另一个 409，不泄露唯一键异常
        putDraft(channel, "req-" + p + "-d1", 0,
                segment(p + "seg-1", p + "movie",
                        "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:01:00.000+08:00"))
                .andExpect(status().isOk());
        List<Integer> publishes = runConcurrently(2, i ->
                publish(channel, "req-" + p + "-cp" + i, 1, 0)
                        .andReturn().getResponse().getStatus());
        assertThat(publishes).containsExactlyInAnyOrder(200, 409);
    }
    // ---------- 测试辅助 ----------

    /** 创建保底素材、普通素材、频道与普通素材的全天授权，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        createGrant(p + "ch", p + "movie");
        return p + "ch";
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions registerDensity(String assetId, String requestId, String operator,
                                          BigDecimal density) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("energyDensity", density);
        return mvc.perform(put("/api/assets/" + assetId + "/energy-density")
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions putDraft(String channel, String requestId, long expectedVersion,
                                   Object... segments) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object segment : segments) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) segment;
            list.add(typed);
        }
        return putDraft(channel, requestId, expectedVersion, list);
    }

    private ResultActions putDraft(String channel, String requestId, long expectedVersion,
                                   List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return mvc.perform(put("/api/channels/" + channel + "/drafts/" + DAY)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions publish(String channel, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private static Map<String, Object> segment(String id, String assetId, String start, String end) {
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

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private ResultActions getJson(String url, String at) throws Exception {
        return mvc.perform(get(url).param("at", at));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private static String prefix() {
        return "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingSupplier<T> {
        T get(int index) throws Exception;
    }

    private static <T> List<T> runConcurrentlyGeneric(int threads, ThrowingSupplier<T> action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
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
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    private static List<Integer> runConcurrently(int threads, ThrowingSupplier<Integer> action)
            throws Exception {
        return runConcurrentlyGeneric(threads, action);
    }

    private static List<String> runConcurrentlyForBody(int threads,
                                                       ThrowingSupplier<String> action)
            throws Exception {
        return runConcurrentlyGeneric(threads, action);
    }
}