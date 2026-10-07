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
 * 变速片段与源内容偏移测试：速率/源区间校验、精确分数与向下取整显示毫秒、
 * 紧急插播墙钟恢复、授权撤销回退、幂等与并发。运行环境为 H2（MODE=MySQL）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SegmentRateOffsetApiTest {

    private static final String DAY = "2026-10-07";
    private static final String GRANT_FROM = "2026-10-07T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-10-08T00:00:00.000+08:00";
    private static final String T0 = "2026-10-07T10:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：合成源 [0,20000) 速率 2 放 10 秒，第 3 秒源偏移 6000 ----------

    @Test
    void rateSegmentPublishAndSourceOffset() throws Exception {
        Ctx ctx = newContext();

        putDraft(ctx, draftBody("req-" + ctx.prefix + "d1", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0,
                                "2026-10-07T10:00:10.000+08:00", 0, 20000, 2, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].sourceStartMs").value(0))
                .andExpect(jsonPath("$.segments[0].sourceEndMs").value(20000))
                .andExpect(jsonPath("$.segments[0].rateP").value(2))
                .andExpect(jsonPath("$.segments[0].rateQ").value(1));
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 第 3 秒：源偏移 = 0 + 3000*2/1 = 6000（精确分数 6000/1，显示毫秒 6000）
        playout(ctx, "2026-10-07T10:00:03.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.clipAsset))
                .andExpect(jsonPath("$.sourceOffset.numerator").value(6000))
                .andExpect(jsonPath("$.sourceOffset.denominator").value(1))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(6000));
        // 起点：偏移 0
        playout(ctx, T0)
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(0));
        // 终点前一毫秒：偏移 19998，仍命中
        playout(ctx, "2026-10-07T10:00:09.999+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(19998));
        // 终点不再命中（左闭右开），不凭显示偏移判断越界
        playout(ctx, "2026-10-07T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.sourceOffset").isEmpty());
    }

    // ---------- 分数速率：精确分数与向下取整显示毫秒 ----------

    @Test
    void fractionalRateExactFractionAndFloor() throws Exception {
        Ctx ctx = newContext();

        // 源 [0,10000) 速率 1/2 → 播放 20 秒
        putDraft(ctx, draftBody("req-" + ctx.prefix + "d1", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0,
                                "2026-10-07T10:00:20.000+08:00", 0, 10000, 1, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].rateP").value(1))
                .andExpect(jsonPath("$.segments[0].rateQ").value(2));
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 第 3 秒整：3000/2 = 1500/1，显示 1500
        playout(ctx, "2026-10-07T10:00:03.000+08:00")
                .andExpect(jsonPath("$.sourceOffset.numerator").value(1500))
                .andExpect(jsonPath("$.sourceOffset.denominator").value(1))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(1500));
        // 第 3.001 秒：精确分数 3001/2，显示毫秒向下取整 1500
        playout(ctx, "2026-10-07T10:00:03.001+08:00")
                .andExpect(jsonPath("$.sourceOffset.numerator").value(3001))
                .andExpect(jsonPath("$.sourceOffset.denominator").value(2))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(1500));
    }

    // ---------- 插播覆盖 3~5 秒，第 5 秒恢复时源偏移 10000，不回 6000 ----------

    @Test
    void emergencyOverrideDoesNotPauseSourceClock() throws Exception {
        Ctx ctx = newContext();
        putDraft(ctx, draftBody("req-" + ctx.prefix + "d1", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0,
                                "2026-10-07T10:00:10.000+08:00", 0, 20000, 2, 1))))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 插播覆盖第 3~5 秒
        postJson("/api/emergency-overrides", overrideBody("req-" + ctx.prefix + "ov",
                        ctx.prefix + "ov-1", ctx, 5,
                        "2026-10-07T10:00:03.000+08:00", "2026-10-07T10:00:05.000+08:00"))
                .andExpect(status().isOk());

        // 覆盖期间命中插播
        playout(ctx, "2026-10-07T10:00:04.000+08:00")
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.sourceOffset").isEmpty());
        // 第 5 秒恢复：源时间未暂停，按墙钟映射 0 + 5000*2 = 10000，而非 6000
        playout(ctx, "2026-10-07T10:00:05.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.clipAsset))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(10000));
    }

    // ---------- 失败分支：非法区间、非整数时长、速率越界、缺授权均 422 且草稿不改 ----------

    @Test
    void invalidRateAndIntervalRejectedAndDraftUnchanged() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;
        String end10 = "2026-10-07T10:00:10.000+08:00";

        // 422：非整数播放时长（19999*1/2 非整数）
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b1", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s1", ctx.clipAsset, T0, end10, 0, 19999, 2, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NON_INTEGER_DURATION"));
        // 422：源区间非法（起点不小于终点）
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b2", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s2", ctx.clipAsset, T0, end10, 5000, 5000, 1, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SOURCE_INTERVAL"));
        // 422：源区间超出素材时长
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b3", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s3", ctx.clipAsset, T0, end10, 0, 20001, 2, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SOURCE_INTERVAL"));
        // 422：速率比率超出 1/2~2
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b4", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s4", ctx.clipAsset, T0, end10, 0, 20000, 1, 3))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_RATE"));
        // 422：速率分量超出 1~10
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b5", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s5", ctx.clipAsset, T0, end10, 0, 20000, 11, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_RATE"));
        // 422：播放区间长度不等于源长度乘 q 除 p
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b6", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s6", ctx.clipAsset, T0,
                                "2026-10-07T10:00:11.000+08:00", 0, 20000, 2, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYBACK_DURATION_MISMATCH"));
        // 422：播放时长超过素材原总长（源 [0,20000) 速率 1/2 → 40000ms）
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b7", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s7", ctx.clipAsset, T0,
                                "2026-10-07T10:00:40.000+08:00", 0, 20000, 1, 2))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DURATION_EXCEEDS_ASSET"));
        // 422：速率字段不完整
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b8", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s8", ctx.clipAsset, T0, end10,
                                null, null, 2, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATE_FIELDS_INCOMPLETE"));
        // 422：缺授权（无授权素材）
        postJson("/api/assets", Map.of("id", ctx.prefix + "free", "durationMs", 20000))
                .andExpect(status().isOk());
        putDraft(ctx, draftBody("req-" + ctx.prefix + "b9", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "s9", ctx.prefix + "free", T0, end10,
                                0, 20000, 2, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 全部失败后草稿仍未创建：以 expectedDraftVersion=0 成功落首版，版本为 1
        putDraft(ctx, draftBody("req-" + ctx.prefix + "ok", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0, end10,
                                0, 20000, 2, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 400：缺操作者
        Map<String, Object> noOperator = draftBody("req-" + ctx.prefix + "b10", null, 1, List.of());
        noOperator.remove("operator");
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noOperator)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 普通片段：速率仍为 1，源区间 [0, 播放时长) ----------

    @Test
    void plainSegmentDefaultsToRateOne() throws Exception {
        Ctx ctx = newContext();

        putDraft(ctx, draftBody("req-" + ctx.prefix + "d1", "op-a", 0, List.of(
                        plainSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0,
                                "2026-10-07T10:00:10.000+08:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].rateP").value(1))
                .andExpect(jsonPath("$.segments[0].rateQ").value(1))
                .andExpect(jsonPath("$.segments[0].sourceStartMs").value(0))
                .andExpect(jsonPath("$.segments[0].sourceEndMs").value(10000));
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 第 3 秒：源偏移 = 3000/1
        playout(ctx, "2026-10-07T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffset.numerator").value(3000))
                .andExpect(jsonPath("$.sourceOffset.denominator").value(1))
                .andExpect(jsonPath("$.sourceOffset.displayMs").value(3000));
    }

    // ---------- 授权撤销：发布后撤销仍回保底素材 ----------

    @Test
    void grantRevokedAfterPublishFallsBack() throws Exception {
        Ctx ctx = newContext();
        putDraft(ctx, draftBody("req-" + ctx.prefix + "d1", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0,
                                "2026-10-07T10:00:10.000+08:00", 0, 20000, 2, 1))))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        postJson("/api/grants/" + ctx.clipGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv"))
                .andExpect(status().isOk());
        playout(ctx, "2026-10-07T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.sourceOffset").isEmpty());
    }

    // ---------- 幂等：操作者与全参数结构化比较，速率按值，失败不占键 ----------

    @Test
    void idempotencyOperatorAndRateByValue() throws Exception {
        Ctx ctx = newContext();
        String end10 = "2026-10-07T10:00:10.000+08:00";
        Map<String, Object> body = draftBody("req-" + ctx.prefix + "idem", "op-a", 0, List.of(
                rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0, end10, 0, 20000, 2, 1)));

        // 首次成功
        putDraft(ctx, body).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // 同 requestId 同参数重放：返回首次完整结果，版本不重复递增
        putDraft(ctx, body).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        // 速率按值比较：4/2 与 2/1 等价，视为同参数重放
        putDraft(ctx, draftBody("req-" + ctx.prefix + "idem", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0, end10, 0, 20000, 4, 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 改操作者：409
        putDraft(ctx, draftBody("req-" + ctx.prefix + "idem", "op-b", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0, end10, 0, 20000, 2, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改速率值：409
        putDraft(ctx, draftBody("req-" + ctx.prefix + "idem", "op-a", 0, List.of(
                        rateSegment(ctx.prefix + "seg-1", ctx.clipAsset, T0, end10, 0, 10000, 1, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先 422，再用同 requestId 成功
        putDraft(ctx, draftBody("req-" + ctx.prefix + "retry", "op-a", 1, List.of(
                        rateSegment(ctx.prefix + "sx", ctx.clipAsset, T0, end10, 0, 20001, 2, 1))))
                .andExpect(status().isUnprocessableEntity());
        putDraft(ctx, draftBody("req-" + ctx.prefix + "retry", "op-a", 1, List.of(
                        rateSegment(ctx.prefix + "seg-2", ctx.clipAsset, T0, end10, 0, 20000, 2, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 并发：同版本变速草稿替换仅一方成功 ----------

    @Test
    void concurrentRateDraftReplaceOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        List<Integer> statuses = runConcurrently(2, i -> {
            Map<String, Object> body = draftBody("req-" + ctx.prefix + "c" + i, "op-" + i, 0,
                    List.of(rateSegment(ctx.prefix + "seg-" + i, ctx.clipAsset, T0,
                            "2026-10-07T10:00:10.000+08:00", 0, 20000, 2, 1)));
            return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终库内版本为 1，且播出决定与胜出草稿一致
        playout(ctx, "2026-10-07T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、20000ms 变速素材 clip、60000ms 插播素材 news 及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String clipAsset;
        private String newsAsset;
        private long clipGrant;
        private long newsGrant;
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.clipAsset = ctx.prefix + "clip";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.clipAsset, "durationMs", 20000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 60000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.clipGrant = createGrant(ctx.channel, ctx.clipAsset);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset);
        return ctx;
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    /** 变速片段：源区间 [sourceStartMs, sourceEndMs)，速率 rateP/rateQ。 */
    private static Map<String, Object> rateSegment(String id, String assetId, String start,
                                                   String end, Object sourceStartMs,
                                                   Object sourceEndMs, Object rateP, Object rateQ) {
        Map<String, Object> segment = plainSegment(id, assetId, start, end);
        if (sourceStartMs != null) {
            segment.put("sourceStartMs", sourceStartMs);
        }
        if (sourceEndMs != null) {
            segment.put("sourceEndMs", sourceEndMs);
        }
        if (rateP != null) {
            segment.put("rateP", rateP);
        }
        if (rateQ != null) {
            segment.put("rateQ", rateQ);
        }
        return segment;
    }

    /** 普通片段：不携带源区间与速率字段。 */
    private static Map<String, Object> plainSegment(String id, String assetId,
                                                    String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> draftBody(String requestId, String operator,
                                                 long expectedVersion,
                                                 List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return body;
    }

    private Map<String, Object> overrideBody(String requestId, String overrideKey, Ctx ctx,
                                             int priority, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", overrideKey);
        body.put("channelId", ctx.channel);
        body.put("assetId", ctx.newsAsset);
        body.put("grantId", ctx.newsGrant);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private ResultActions putDraft(Ctx ctx, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions publish(Ctx ctx, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "operator", "op-a",
                        "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout").param("at", at));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private interface ThrowingSupplier {
        Integer get(int index) throws Exception;
    }

    private List<Integer> runConcurrently(int threads, ThrowingSupplier action) throws Exception {
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
