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
 * 直播覆盖令 API 端到端测试：主流程、恢复边界、失败回滚、幂等、授权失效回退与并发边界。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveOverrideApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：创建、命中、结束边界、取消释放 ----------

    @Test
    void createHitEndBoundaryCancelAndRelease() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-a", T_10, T_11);

        // 创建即 ACTIVE；区间 10:20-10:40
        createOverride(ctx, "req-create", "ov-1", ctx.newsAsset, ctx.newsGrant, T_1020, T_1040)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.liveAssetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant))
                .andExpect(jsonPath("$.cancelRequestId").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // 区间外仍是原节目
        playout(ctx, "2026-09-22T10:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
        // 区间内：LIVE 覆盖原节目，返回直播源素材与 overrideKey
        playout(ctx, T_1020)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.publicationId").isEmpty())
                .andExpect(jsonPath("$.reason").isEmpty());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("LIVE"));
        // 结束时刻不再命中（左闭右开）
        playout(ctx, T_1040)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 取消
        postJson("/api/live-overrides/" + ctx.key("ov-1") + "/cancel",
                        Map.of("requestId", "req-cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value("req-cancel"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        // 明细保留取消情况与原授权关联
        getOverride(ctx, "ov-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant))
                .andExpect(jsonPath("$.cancelRequestId").value("req-cancel"));

        // 取消后播出回到原节目
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 取消提交后释放该冲突范围：与已取消区间重叠可再创建
        createOverride(ctx, "req-create-2", "ov-2", ctx.newsAsset, ctx.newsGrant, T_1020, T_1040)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 恢复边界：覆盖结束后按原时间轴恢复，被覆盖部分不补播 ----------

    @Test
    void recoveryBoundaryFollowsOriginalTimeline() throws Exception {
        Ctx ctx = newContext();
        // 原编排：10:00-10:30 movie，10:30-11:00 news
        publishProgram2(ctx, "seg-a", ctx.movieAsset, T_10, T_1030,
                "seg-b", ctx.newsAsset, T_1030, T_11);

        // 直播覆盖 10:20-10:40，跨过片段边界
        createOverride(ctx, "req-live", "live-1", ctx.alertAsset, ctx.alertGrant, T_1020, T_1040)
                .andExpect(status().isOk());

        // 覆盖前：节目 A
        playout(ctx, "2026-09-22T10:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
        // 覆盖中：LIVE
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.alertAsset));
        // 覆盖后：按原时间轴恢复到节目 B（10:50 落在 B 区间）；A 被覆盖部分不补播
        playout(ctx, "2026-09-22T10:50:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.overrideKey").isEmpty());
        // 11:00 后原编排已结束：不补播被覆盖内容，走保底空档
        playout(ctx, "2026-09-22T11:00:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset));
    }

    // ---------- 区间规则：相邻合法、跨日与长时长合法、列表稳定排序 ----------

    @Test
    void adjacentCrossDayLongDurationAndStableListOrder() throws Exception {
        Ctx ctx = newContext();

        // 相邻区间合法（先建后面的，验证列表按开始时间排序而非创建顺序）
        createOverride(ctx, "r2", "b", ctx.newsAsset, ctx.newsGrant, T_1030, T_1040)
                .andExpect(status().isOk());
        createOverride(ctx, "r1", "a", ctx.newsAsset, ctx.newsGrant, T_1020, T_1030)
                .andExpect(status().isOk());

        // 长时长（2.5 小时，超过紧急插播 30 分钟上限）合法
        createOverride(ctx, "r3", "long", ctx.newsAsset, ctx.newsGrant,
                        "2026-09-22T14:00:00.000+08:00", "2026-09-22T16:30:00.000+08:00")
                .andExpect(status().isOk());

        // 跨日区间合法：授权 09-22 00:00 ~ 09-24 00:00，覆盖 09-22 23:50 ~ 09-23 00:10
        long crossGrant = createGrant(ctx.channel, ctx.alertAsset,
                GRANT_FROM, "2026-09-24T00:00:00.000+08:00");
        createOverrideRaw("r4", ctx.key("cross"), ctx.channel, ctx.alertAsset, crossGrant,
                        "2026-09-22T23:50:00.000+08:00", "2026-09-23T00:10:00.000+08:00")
                .andExpect(status().isOk());
        playout(ctx, "2026-09-22T23:55:00.000+08:00")
                .andExpect(jsonPath("$.source").value("LIVE"));
        playout(ctx, "2026-09-23T00:05:00.000+08:00")
                .andExpect(jsonPath("$.source").value("LIVE"));
        // 结束时刻不再命中（左闭右开）
        playout(ctx, "2026-09-23T00:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"));

        // 列表按开始时间、键稳定排序：a(10:20) < b(10:30) < long(14:00) < cross(23:50)
        mvc.perform(get("/api/channels/" + ctx.channel + "/live-overrides"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].overrideKey").value(ctx.key("a")))
                .andExpect(jsonPath("$[1].overrideKey").value(ctx.key("b")))
                .andExpect(jsonPath("$[2].overrideKey").value(ctx.key("long")))
                .andExpect(jsonPath("$[3].overrideKey").value(ctx.key("cross")));
        // 未知频道：404
        mvc.perform(get("/api/channels/" + ctx.prefix + "ghost/live-overrides"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidIntervalsReturnBadRequest() throws Exception {
        Ctx ctx = newContext();

        // 400：结束不大于开始
        createOverride(ctx, "bad-1", "k1", ctx.newsAsset, ctx.newsGrant, T_1030, T_1020)
                .andExpect(status().isBadRequest());
        createOverride(ctx, "bad-2", "k2", ctx.newsAsset, ctx.newsGrant, T_1030, T_1030)
                .andExpect(status().isBadRequest());
        // 400：缺少 requestId
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideBody(
                                null, ctx.key("k3"), ctx.channel, ctx.newsAsset,
                                ctx.newsGrant, T_1020, T_1030))))
                .andExpect(status().isBadRequest());

        // 失败回滚：非法参数不产生覆盖令记录
        getOverride(ctx, "k1").andExpect(status().isNotFound());
        getOverride(ctx, "k2").andExpect(status().isNotFound());
    }

    // ---------- 404 / 409 / 422 分支 ----------

    @Test
    void notFoundConflictAndUnprocessableBranches() throws Exception {
        Ctx ctx = newContext();

        // 404：频道/素材/授权/明细/取消不存在
        createOverride(ctx, "nf-1", "nf1", ctx.newsAsset, ctx.newsGrant, T_1020, T_1030)
                .andExpect(status().isOk());
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideBody(
                                "nf-2", ctx.key("nf2"), ctx.prefix + "ghost", ctx.newsAsset,
                                ctx.newsGrant, T_1020, T_1030))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideBody(
                                "nf-3", ctx.key("nf3"), ctx.channel, ctx.prefix + "ghost",
                                ctx.newsGrant, T_1020, T_1030))))
                .andExpect(status().isNotFound());
        createOverrideRaw("nf-4", ctx.key("nf4"), ctx.channel, ctx.newsAsset, 999999,
                        T_1020, T_1030)
                .andExpect(status().isNotFound());
        getOverrideRaw(ctx.prefix + "ghost").andExpect(status().isNotFound());
        postJson("/api/live-overrides/" + ctx.prefix + "ghost/cancel",
                        Map.of("requestId", "nf-5"))
                .andExpect(status().isNotFound());

        // 422：授权不属于该频道
        Ctx other = newContext();
        createOverrideRaw("nm-1", ctx.key("nm1"), ctx.channel, ctx.newsAsset,
                        other.newsGrant, T_1020, T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));
        // 422：授权素材不匹配（movie 的授权用于 news 直播源）
        createOverrideRaw("nm-2", ctx.key("nm2"), ctx.channel, ctx.newsAsset,
                        ctx.movieGrant, T_1020, T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));

        // 422：授权未完整覆盖区间（授权 10:00-10:20，覆盖 10:10-10:30）
        long shortGrant = createGrant(ctx.channel, ctx.newsAsset,
                "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:20:00.000+08:00");
        createOverrideRaw("nc-1", ctx.key("nc1"), ctx.channel, ctx.newsAsset,
                        shortGrant, "2026-09-22T10:10:00.000+08:00", T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"));

        // 422：保底素材不能用于直播覆盖
        createOverrideRaw("fb-1", ctx.key("fb1"), ctx.channel, ctx.fallbackAsset,
                        ctx.newsGrant, T_1020, T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));

        // 422：指定授权已撤销
        long revokedGrant = createGrant(ctx.channel, ctx.alertAsset, GRANT_FROM, GRANT_TO);
        postJson("/api/grants/" + revokedGrant + "/revoke", Map.of("requestId", "rv-g"))
                .andExpect(status().isOk());
        createOverrideRaw("rv-1", ctx.key("rv1"), ctx.channel, ctx.alertAsset,
                        revokedGrant, T_1020, T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 409：同频道 ACTIVE 区间重叠（与 nf1 的 10:20-10:30 相交）
        createOverrideRaw("ov-2", ctx.key("ov-2"), ctx.channel, ctx.alertAsset,
                        ctx.alertGrant, "2026-09-22T10:25:00.000+08:00",
                        "2026-09-22T10:35:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));

        // 409：重复 overrideKey
        createOverrideRaw("dup-1", ctx.key("nf1"), ctx.channel, ctx.newsAsset,
                        ctx.newsGrant, "2026-09-22T15:00:00.000+08:00",
                        "2026-09-22T15:10:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_OVERRIDE_KEY"));

        // 409：取消 ACTIVE 后再次取消
        postJson("/api/live-overrides/" + ctx.key("nf1") + "/cancel",
                        Map.of("requestId", "cancel-nf1"))
                .andExpect(status().isOk());
        postJson("/api/live-overrides/" + ctx.key("nf1") + "/cancel",
                        Map.of("requestId", "cancel-nf1-again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_NOT_ACTIVE"));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotencyReplayChangedParamsFailureAndNoResurrection() throws Exception {
        Ctx ctx = newContext();

        Map<String, Object> body = overrideBody("idem-1", ctx.key("io"), ctx.channel,
                ctx.newsAsset, ctx.newsGrant, T_1020, T_1030);
        // 首次成功
        postJson("/api/live-overrides", body).andExpect(status().isOk());
        // 同 requestId 同参数重放：返回首次结果
        postJson("/api/live-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("io")));
        // 同 requestId 改参数（直播源素材）：409
        postJson("/api/live-overrides", overrideBody("idem-1", ctx.key("io"), ctx.channel,
                        ctx.alertAsset, ctx.alertGrant, T_1020, T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改键：409
        postJson("/api/live-overrides", overrideBody("idem-1", ctx.key("io2"), ctx.channel,
                        ctx.newsAsset, ctx.newsGrant, T_1020, T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId、不占 overrideKey：先因授权不覆盖 422，再用同 requestId 同键成功
        long shortGrant = createGrant(ctx.channel, ctx.newsAsset,
                "2026-09-22T11:00:00.000+08:00", "2026-09-22T11:20:00.000+08:00");
        createOverrideRaw("retry-1", ctx.key("rk"), ctx.channel, ctx.newsAsset, shortGrant,
                        "2026-09-22T11:10:00.000+08:00", "2026-09-22T11:30:00.000+08:00")
                .andExpect(status().isUnprocessableEntity());
        getOverride(ctx, "rk").andExpect(status().isNotFound());
        createOverrideRaw("retry-1", ctx.key("rk"), ctx.channel, ctx.newsAsset, ctx.newsGrant,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("rk")));

        // 取消幂等：同 requestId 重放返回首次的 CANCELLED 明细
        postJson("/api/live-overrides/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", "cancel-rk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/live-overrides/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", "cancel-rk"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 同 requestId 用于取消其他覆盖令：409
        postJson("/api/live-overrides/" + ctx.key("io") + "/cancel",
                        Map.of("requestId", "cancel-rk"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 重放创建不能复活已取消覆盖令：新 requestId 同键再创建 → 409，明细仍 CANCELLED
        createOverride(ctx, "resurrect-1", "rk", ctx.newsAsset, ctx.newsGrant,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_OVERRIDE_KEY"));
        getOverride(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
        // 原创建 requestId 重放只返回历史响应，不改变 CANCELLED 状态
        createOverrideRaw("retry-1", ctx.key("rk"), ctx.channel, ctx.newsAsset, ctx.newsGrant,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getOverride(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- 授权失效回退与紧急插播优先级 ----------

    @Test
    void grantRevokedFallsToProgramAndEmergencyWins() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "prog", T_10, T_11);

        createOverride(ctx, "live", "live-1", ctx.newsAsset, ctx.newsGrant, T_1020, T_1040)
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("LIVE"));

        // 紧急插播优先于直播覆盖
        postJson("/api/emergency-overrides", emergencyBody("em-1", ctx.key("em-1"),
                        ctx.channel, ctx.alertAsset, ctx.alertGrant, 5, T_1020, T_1040))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.alertAsset));
        // 取消紧急插播后回到直播覆盖
        postJson("/api/emergency-overrides/" + ctx.key("em-1") + "/cancel",
                        Map.of("requestId", "em-cancel"))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset));

        // 直播源授权撤销：覆盖令不生效，落回原节目；状态与授权关联不被改写
        postJson("/api/grants/" + ctx.newsGrant + "/revoke", Map.of("requestId", "rv-news"))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.overrideKey").isEmpty());
        getOverride(ctx, "live-1")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant));

        // 无已发布节目且覆盖令授权失效：走保底
        Ctx bare = newContext();
        createOverride(bare, "only", "only", bare.newsAsset, bare.newsGrant, T_1020, T_1040)
                .andExpect(status().isOk());
        postJson("/api/grants/" + bare.newsGrant + "/revoke", Map.of("requestId", "rv-only"))
                .andExpect(status().isOk());
        playout(bare, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(bare.fallbackAsset));
    }

    // ---------- 并发：重叠最多一条成功；相邻均成功 ----------

    @Test
    void concurrentOverlapOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/live-overrides";

        // 两个同频道重叠覆盖令并发创建：恰好一条成功
        List<Integer> overlap = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overrideBody(
                                "cc-req-" + ctx.prefix + i, ctx.key("cc" + i), ctx.channel,
                                ctx.newsAsset, ctx.newsGrant, T_1020, T_1040))))
                .andReturn().getResponse().getStatus());
        assertThat(overlap).containsExactlyInAnyOrder(200, 409);

        // 两条相邻区间并发创建：均成功
        List<Integer> adjacent = runConcurrently(2, i -> {
            String start = i == 0 ? "2026-09-22T15:00:00.000+08:00" : "2026-09-22T15:15:00.000+08:00";
            String end = i == 0 ? "2026-09-22T15:15:00.000+08:00" : "2026-09-22T15:30:00.000+08:00";
            return mvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(overrideBody(
                                    "ad-req-" + ctx.prefix + i, ctx.key("ad" + i), ctx.channel,
                                    ctx.newsAsset, ctx.newsGrant, start, end))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(adjacent).containsExactly(200, 200);

        // 最终库内状态：重叠区间只有一条 ACTIVE
        int activeCount = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult result = getOverride(ctx, "cc" + i).andReturn();
            if (result.getResponse().getStatus() == 200
                    && readJson(result).get("status").asText().equals("ACTIVE")) {
                activeCount++;
            }
        }
        assertThat(activeCount).isEqualTo(1);
    }

    // ---------- 并发：创建与授权撤销按提交顺序处理 ----------

    @Test
    void concurrentCreateAndRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "prog", T_10, T_11);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> createFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/live-overrides")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(overrideBody(
                                        "race-req", ctx.key("race"), ctx.channel,
                                        ctx.newsAsset, ctx.newsGrant, T_1020, T_1040))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/grants/" + ctx.newsGrant + "/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("requestId", "race-rv"))))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int createStatus = createFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            // 撤销本身成功；创建要么 422（撤销先提交），要么 200（创建先提交）
            assertThat(revokeStatus).isEqualTo(200);
            assertThat(createStatus).isIn(200, 422);

            MvcResult detail = getOverride(ctx, "race").andReturn();
            if (createStatus == 422) {
                // 撤销先提交：覆盖令不存在
                assertThat(detail.getResponse().getStatus()).isEqualTo(404);
            } else {
                // 创建先提交：覆盖令保留 ACTIVE 与原授权关联，但后续播出必须排除该覆盖令
                JsonNode json = readJson(detail);
                assertThat(detail.getResponse().getStatus()).isEqualTo(200);
                assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
                assertThat(json.get("grantId").asLong()).isEqualTo(ctx.newsGrant);
            }
            // 无论提交顺序如何，授权已撤销，10:30 都不能命中 LIVE
            playout(ctx, T_1030)
                    .andExpect(jsonPath("$.source").value("PROGRAM"))
                    .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、普通素材 movie/news/alert、频道及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
        private String alertAsset;
        private long movieGrant;
        private long newsGrant;
        private long alertGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.alertAsset = ctx.prefix + "alert";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.alertAsset, "durationMs", 900000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        ctx.alertGrant = createGrant(ctx.channel, ctx.alertAsset, GRANT_FROM, GRANT_TO);
        return ctx;
    }

    /** 发布一段节目，作为覆盖令下层的原节目。 */
    private void publishProgram(Ctx ctx, String segmentId, String start, String end) throws Exception {
        publishSegments(ctx, "pub-" + segmentId, List.of(segment(ctx, segmentId, ctx.movieAsset, start, end)));
    }

    /** 发布两段相接节目，用于恢复边界测试。 */
    private void publishProgram2(Ctx ctx, String id1, String asset1, String start1, String end1,
                                 String id2, String asset2, String start2, String end2) throws Exception {
        publishSegments(ctx, "pub-" + id1, List.of(
                segment(ctx, id1, asset1, start1, end1),
                segment(ctx, id2, asset2, start2, end2)));
    }

    private Map<String, Object> segment(Ctx ctx, String id, String assetId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.prefix + id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private void publishSegments(Ctx ctx, String tag, List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", "draft-" + ctx.prefix + tag);
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "pub-" + ctx.prefix + tag,
                                "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isOk());
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions createOverride(Ctx ctx, String requestId, String shortKey, String liveAssetId,
                                         long grantId, String start, String end) throws Exception {
        return createOverrideRaw(requestId, ctx.key(shortKey), ctx.channel, liveAssetId,
                grantId, start, end);
    }

    private ResultActions createOverrideRaw(String requestId, String overrideKey, String channelId,
                                            String liveAssetId, long grantId,
                                            String start, String end) throws Exception {
        return postJson("/api/live-overrides",
                overrideBody(requestId, overrideKey, channelId, liveAssetId, grantId, start, end));
    }

    private static Map<String, Object> overrideBody(String requestId, String overrideKey,
                                                    String channelId, String liveAssetId, long grantId,
                                                    String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", overrideKey);
        body.put("channelId", channelId);
        body.put("liveAssetId", liveAssetId);
        body.put("grantId", grantId);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> emergencyBody(String requestId, String overrideKey,
                                                  String channelId, String assetId, long grantId,
                                                  int priority, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", overrideKey);
        body.put("channelId", channelId);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout").param("at", at));
    }

    private ResultActions getOverride(Ctx ctx, String shortKey) throws Exception {
        return getOverrideRaw(ctx.key(shortKey));
    }

    private ResultActions getOverrideRaw(String overrideKey) throws Exception {
        return mvc.perform(get("/api/live-overrides/" + overrideKey));
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
