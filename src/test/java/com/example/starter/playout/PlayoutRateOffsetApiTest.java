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
 * 有理数速率与源内容偏移端到端测试：合成场景、精确分数与向下取整、422 校验、
 * 发布冻结、授权撤销回退、幂等与并发。运行环境为 H2（MODE=MySQL）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutRateOffsetApiTest {

    private static final String DAY = "2026-10-06";
    private static final String GRANT_FROM = "2026-10-06T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-10-07T00:00:00.000+08:00";
    private static final String T0 = "2026-10-06T10:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 合成场景：源 [0,20000) 速率 2 放 10 秒，插播 3~5 秒 ----------

    @Test
    void syntheticRateTwoSegmentAndEmergencyWallClockResume() throws Exception {
        Ctx ctx = newContext(20000);

        // 源 [0,20000) 速率 2/1 → 播放 10 秒（10:00:00 ~ 10:00:10）
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                rateSegment(ctx.prefix + "seg", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-06T10:00:10.000+08:00"))
                .andExpect(jsonPath("$.segments[0].sourceStartMs").value(0))
                .andExpect(jsonPath("$.segments[0].sourceEndMs").value(20000))
                .andExpect(jsonPath("$.segments[0].rateP").value(2))
                .andExpect(jsonPath("$.segments[0].rateQ").value(1));
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 第 3 秒：源偏移 = 0 + 3000×2/1 = 6000（精确分数 6000/1，显示 6000）
        playout(ctx, "2026-10-06T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffsetNum").value(6000))
                .andExpect(jsonPath("$.sourceOffsetDen").value(1))
                .andExpect(jsonPath("$.sourceOffsetMs").value(6000));

        // 紧急插播覆盖第 3 至 5 秒
        createOverride(ctx, "req-" + ctx.prefix + "ov", "ov1", 5,
                "2026-10-06T10:00:03.000+08:00", "2026-10-06T10:00:05.000+08:00")
                .andExpect(status().isOk());
        playout(ctx, "2026-10-06T10:00:04.000+08:00")
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.sourceOffsetMs").isEmpty());

        // 第 5 秒恢复：按墙钟继续映射，源偏移 10000，不回 6000；插播终点不再命中
        playout(ctx, "2026-10-06T10:00:05.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffsetNum").value(10000))
                .andExpect(jsonPath("$.sourceOffsetDen").value(1))
                .andExpect(jsonPath("$.sourceOffsetMs").value(10000));

        // 片段播放终点不再命中（左闭右开）
        playout(ctx, "2026-10-06T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.sourceOffsetMs").isEmpty());
    }

    // ---------- 精确分数与向下取整显示毫秒 ----------

    @Test
    void fractionalOffsetReturnsExactFractionAndFlooredMs() throws Exception {
        Ctx ctx = newContext(40000);

        // 源 [0,10000) 速率 1/2 → 播放 20000 毫秒（11:00:00 ~ 11:00:20）
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                rateSegment(ctx.prefix + "seg", ctx.mainAsset,
                                        "2026-10-06T11:00:00.000+08:00", 0, 10000, 1, 2)))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // t=3001ms：偏移 = 3001×1/2 = 3001/2，显示向下取整 1500
        playout(ctx, "2026-10-06T11:00:03.001+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffsetNum").value(3001))
                .andExpect(jsonPath("$.sourceOffsetDen").value(2))
                .andExpect(jsonPath("$.sourceOffsetMs").value(1500));
        // t=0：偏移 0/1
        playout(ctx, "2026-10-06T11:00:00.000+08:00")
                .andExpect(jsonPath("$.sourceOffsetNum").value(0))
                .andExpect(jsonPath("$.sourceOffsetDen").value(1))
                .andExpect(jsonPath("$.sourceOffsetMs").value(0));
        // t=19999ms：偏移 = 19999/2 = 9999.5，显示 9999，仍命中片段（不以显示值判越界）
        playout(ctx, "2026-10-06T11:00:19.999+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffsetNum").value(19999))
                .andExpect(jsonPath("$.sourceOffsetDen").value(2))
                .andExpect(jsonPath("$.sourceOffsetMs").value(9999));
    }

    // ---------- 422 校验分支：非法区间、非整数时长、缺授权，草稿不被污染 ----------

    @Test
    void invalidRateSegmentsReturn422AndKeepDraft() throws Exception {
        Ctx ctx = newContext(20000);
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        // 422：比率超出 1/2~2（3/1 与 1/10）
        putJson(url, draftBody("req-" + ctx.prefix + "r1", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 10000, 3, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_RATE"));
        putJson(url, draftBody("req-" + ctx.prefix + "r2", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 10000, 1, 10)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_RATE"));
        // 422：p 超出 1~10
        putJson(url, draftBody("req-" + ctx.prefix + "r3", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 10000, 11, 10)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_RATE"));
        // 422：源区间非法（终点不大于起点、负起点、超出素材总长）
        putJson(url, draftBody("req-" + ctx.prefix + "r4", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 10000, 10000, 1, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SOURCE_INTERVAL"));
        putJson(url, draftBody("req-" + ctx.prefix + "r5", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, -1, 10000, 1, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SOURCE_INTERVAL"));
        putJson(url, draftBody("req-" + ctx.prefix + "r6", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 20001, 1, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SOURCE_INTERVAL"));
        // 422：播放时长非整数毫秒（1001×1/2）
        putJson(url, draftBody("req-" + ctx.prefix + "r7", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 1001, 2, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NON_INTEGER_DURATION"));
        // 422：播放时长超过素材原总长（源 [0,15000) 速率 1/2 → 30000 > 20000）
        putJson(url, draftBody("req-" + ctx.prefix + "r8", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 15000, 1, 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DURATION_EXCEEDS_ASSET"));
        // 422：提供的结束时间与速率推导时长不一致
        putJson(url, draftBody("req-" + ctx.prefix + "r9", 0,
                        rateSegmentWithEnd(ctx.prefix + "s", ctx.mainAsset, T0,
                                "2026-10-06T10:00:30.000+08:00", 0, 20000, 2, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_PLAY_INTERVAL"));
        // 422：缺授权（news 素材授权被撤销后）
        postJson("/api/grants/" + ctx.newsGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv", "operator", "tester"))
                .andExpect(status().isOk());
        putJson(url, draftBody("req-" + ctx.prefix + "r10", 0,
                        rateSegment(ctx.prefix + "s", ctx.newsAsset, T0, 0, 10000, 1, 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));
        // 400：变速字段只给一部分
        putJson(url, draftBody("req-" + ctx.prefix + "r11", 0,
                        partialRateSegment(ctx.prefix + "s", ctx.mainAsset, T0)))
                .andExpect(status().isBadRequest());

        // 以上失败均未建立草稿：用 expectedDraftVersion=0 仍可首次创建
        putJson(url, draftBody("req-" + ctx.prefix + "ok", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 提供的结束时间与推导一致时合法
        putJson(url, draftBody("req-" + ctx.prefix + "ok2", 1,
                        rateSegmentWithEnd(ctx.prefix + "s", ctx.mainAsset, T0,
                                "2026-10-06T10:00:10.000+08:00", 0, 20000, 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 普通片段速率恒 1 ----------

    @Test
    void normalSegmentKeepsRateOneAndOffsetFromZero() throws Exception {
        Ctx ctx = newContext(3600000);

        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                normalSegment(ctx.prefix + "seg", ctx.mainAsset,
                                        "2026-10-06T13:00:00.000+08:00",
                                        "2026-10-06T13:10:00.000+08:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].rateP").value(1))
                .andExpect(jsonPath("$.segments[0].rateQ").value(1))
                .andExpect(jsonPath("$.segments[0].sourceStartMs").value(0))
                .andExpect(jsonPath("$.segments[0].sourceEndMs").value(600000));
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 速率 1：源偏移 = 播放经过时间
        playout(ctx, "2026-10-06T13:00:03.500+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.sourceOffsetNum").value(3500))
                .andExpect(jsonPath("$.sourceOffsetDen").value(1))
                .andExpect(jsonPath("$.sourceOffsetMs").value(3500));
    }

    // ---------- 发布冻结源区间与约分速率，历史不更新 ----------

    @Test
    void publishFreezesRateAndLaterDraftDoesNotRewriteHistory() throws Exception {
        Ctx ctx = newContext(20000);

        // v1：速率 2/1（2/4 约分）发布
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                rateSegment(ctx.prefix + "seg", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 草稿替换为速率 1/1 同区间片段（未发布）：播出仍按已发布快照的速率 2
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d2", 1,
                                rateSegment(ctx.prefix + "seg", ctx.mainAsset, T0, 0, 10000, 1, 1)))
                .andExpect(status().isOk());
        playout(ctx, "2026-10-06T10:00:03.000+08:00")
                .andExpect(jsonPath("$.sourceOffsetNum").value(6000))
                .andExpect(jsonPath("$.sourceOffsetMs").value(6000));

        // 发布 v2 后按新速率 1/1 映射
        publish(ctx, "req-" + ctx.prefix + "p2", 2, 1).andExpect(status().isOk());
        playout(ctx, "2026-10-06T10:00:03.000+08:00")
                .andExpect(jsonPath("$.sourceOffsetNum").value(3000))
                .andExpect(jsonPath("$.sourceOffsetMs").value(3000));
    }

    // ---------- 授权撤销回保底，偏移字段为空 ----------

    @Test
    void grantRevokedFallsBackAndOffsetFieldsAreNull() throws Exception {
        Ctx ctx = newContext(20000);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                rateSegment(ctx.prefix + "seg", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        postJson("/api/grants/" + ctx.mainGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv", "operator", "tester"))
                .andExpect(status().isOk());
        playout(ctx, "2026-10-06T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.sourceOffsetNum").isEmpty())
                .andExpect(jsonPath("$.sourceOffsetDen").isEmpty())
                .andExpect(jsonPath("$.sourceOffsetMs").isEmpty());
    }

    // ---------- 幂等：速率按约分值比较，同参重放、改参 409、失败不占键 ----------

    @Test
    void idempotencyReducedRateReplayChangedParamsAndFailure() throws Exception {
        Ctx ctx = newContext(40000);
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        // 首次：速率 2/4（约分为 1/2），源 [0,5000) → 播放 10000 毫秒
        putJson(url, draftBody("req-" + ctx.prefix + "idem", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 5000, 2, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].rateP").value(1))
                .andExpect(jsonPath("$.segments[0].rateQ").value(2))
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-06T10:00:10.000+08:00"));
        // 同 requestId、速率写成 1/2（按值相等）：返回首次完整结果，版本不重复递增
        putJson(url, draftBody("req-" + ctx.prefix + "idem", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 5000, 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 改参数（速率 1/1）：409
        putJson(url, draftBody("req-" + ctx.prefix + "idem", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 5000, 1, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以非法速率 422，再用同 requestId 成功
        putJson(url, draftBody("req-" + ctx.prefix + "retry", 1,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 5000, 3, 1)))
                .andExpect(status().isUnprocessableEntity());
        putJson(url, draftBody("req-" + ctx.prefix + "retry", 1,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 5000, 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 写入必须携带非空操作者 ----------

    @Test
    void writesRequireNonBlankOperator() throws Exception {
        Ctx ctx = newContext(20000);
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        Map<String, Object> missing = draftBody("req-" + ctx.prefix + "op1", 0,
                rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 20000, 2, 1));
        missing.remove("operator");
        putJson(url, missing).andExpect(status().isBadRequest());

        Map<String, Object> blank = draftBody("req-" + ctx.prefix + "op2", 0,
                rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 20000, 2, 1));
        blank.put("operator", "  ");
        putJson(url, blank).andExpect(status().isBadRequest());

        // 撤销授权同样要求非空操作者
        postJson("/api/grants/" + ctx.mainGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "op3"))
                .andExpect(status().isBadRequest());

        // 失败未占用 requestId：补上操作者后成功
        putJson(url, draftBody("req-" + ctx.prefix + "op1", 0,
                        rateSegment(ctx.prefix + "s", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 并发：同版本变速草稿替换仅一方成功，最终状态一致 ----------

    @Test
    void concurrentRateDraftReplaceOnlyOneWins() throws Exception {
        Ctx ctx = newContext(20000);
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;
        putJson(url, draftBody("req-" + ctx.prefix + "d0", 0,
                        rateSegment(ctx.prefix + "s0", ctx.mainAsset, T0, 0, 20000, 2, 1)))
                .andExpect(status().isOk());

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(put(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(draftBody("req-" + ctx.prefix + "c" + i, 1,
                                rateSegment(ctx.prefix + "s" + i, ctx.mainAsset, T0,
                                        0, 20000, i == 0 ? 2 : 1, 1)))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终草稿版本为 2 且可发布：证明只有一方落库，状态一致
        publish(ctx, "req-" + ctx.prefix + "pub", 2, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
        playout(ctx, "2026-10-06T10:00:03.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.mainAsset));
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、主素材（时长可配）、插播素材、频道及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "r" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String mainAsset;
        private String newsAsset;
        private long mainGrant;
        private long newsGrant;
    }

    private Ctx newContext(long mainDurationMs) throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.mainAsset = ctx.prefix + "main";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.channel = ctx.prefix + "ch";
        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.mainAsset, "durationMs", mainDurationMs))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 20000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.mainGrant = createGrant(ctx.channel, ctx.mainAsset);
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

    /** 变速片段：不携带 end，由服务端按源长度乘 q 除 p 推导。 */
    private static Map<String, Object> rateSegment(String id, String assetId, String start,
                                                   long sourceStart, long sourceEnd, int p, int q) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("sourceStartMs", sourceStart);
        segment.put("sourceEndMs", sourceEnd);
        segment.put("rateP", p);
        segment.put("rateQ", q);
        return segment;
    }

    /** 变速片段：显式携带 end。 */
    private static Map<String, Object> rateSegmentWithEnd(String id, String assetId, String start,
                                                          String end, long sourceStart,
                                                          long sourceEnd, int p, int q) {
        Map<String, Object> segment = rateSegment(id, assetId, start, sourceStart, sourceEnd, p, q);
        segment.put("end", end);
        return segment;
    }

    /** 只给部分变速字段的非法片段。 */
    private static Map<String, Object> partialRateSegment(String id, String assetId, String start) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("sourceStartMs", 0);
        segment.put("rateP", 2);
        return segment;
    }

    private static Map<String, Object> normalSegment(String id, String assetId,
                                                     String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 Map<String, Object>... segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segments));
        return body;
    }

    private ResultActions publish(Ctx ctx, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "operator", "tester",
                        "draftVersion", draftVersion, "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions createOverride(Ctx ctx, String requestId, String key, int priority,
                                         String start, String end) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("overrideKey", ctx.prefix + key);
        body.put("channelId", ctx.channel);
        body.put("assetId", ctx.newsAsset);
        body.put("grantId", ctx.newsGrant);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return postJson("/api/emergency-overrides", body);
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout").param("at", at))
                .andExpect(status().isOk());
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
