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
 * 频道屏蔽窗口 API 端到端测试：窗口校验、替补回退（紧急插播/节目/保底）、取消即失效、
 * 重叠与并发一致查询、幂等边界。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BlackoutWindowApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_14 = "2026-09-22T14:00:00.000+08:00";
    private static final String T_20 = "2026-09-22T20:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：创建、命中、边界、明细 ----------

    @Test
    void createWindowSubstitutesBlockedProgramAndBoundaries() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);

        createWindow(ctx, "w-req", "w1", T_1020, T_1040,
                        List.of(ctx.movieAsset, ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("w1")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.substituteAssetId").value(ctx.fillerAsset))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.blockedAssetIds[1]").value(ctx.newsAsset))
                .andExpect(jsonPath("$.cancelRequestId").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isEmpty());

        // 窗口外：原节目
        playout(ctx, "2026-09-22T10:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.blackoutKey").isEmpty());
        // 起点（含）：节目素材被屏蔽 → 替补
        playout(ctx, T_1020)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.fillerAsset))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("w1")))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.replacedSource").value("PROGRAM"))
                .andExpect(jsonPath("$.publicationId").isNotEmpty())
                .andExpect(jsonPath("$.segmentId").value(ctx.prefix + "seg"))
                .andExpect(jsonPath("$.reason").isEmpty());
        // 终点（不含）：回到原节目
        playout(ctx, T_1040)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 窗口内但原素材不在集合中：频道上再加一个只屏蔽 news 的窗口外，这里通过无插播场景验证：
        // 当前窗口集合含 movie/news，保底不会被选中（有节目）；用另一条相邻窗口验证不屏蔽即原样
        createWindow(ctx, "w-req-2", "w2", "2026-09-22T11:00:00.000+08:00",
                        "2026-09-22T12:00:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk());
        // 11:30 无节目（GAP 走保底 fallback），fallback 不在被屏蔽集合 → 原样 FALLBACK
        playout(ctx, "2026-09-22T11:30:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.blackoutKey").isEmpty());
    }

    // ---------- 替补回退：紧急插播 / 保底 / 授权撤销保底 三种原来源 ----------

    @Test
    void substituteAppliesToEmergencyFallbackAndGrantRevoked() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);

        // 紧急插播 news 与窗口同区间，news 被屏蔽 → SUBSTITUTE，replacedSource=EMERGENCY，保留 overrideKey
        createOverride(ctx, "ov-req", "ov1", ctx.newsAsset, ctx.newsGrant, 5, T_1020, T_1040)
                .andExpect(status().isOk());
        createWindow(ctx, "bw-em", "em", T_1020, T_1040,
                        List.of(ctx.newsAsset, ctx.movieAsset, ctx.fallbackAsset), ctx.fillerAsset)
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.fillerAsset))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.replacedSource").value("EMERGENCY"))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov1")))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("em")));
        // 插播记录不被屏蔽改写
        getOverride(ctx, "ov1").andExpect(jsonPath("$.status").value("ACTIVE"));

        // 无已发布编排的频道：原 FALLBACK/NO_PUBLISHED_SCHEDULE，保底素材被屏蔽 → SUBSTITUTE
        Ctx bare = newContext();
        createWindow(bare, "bw-fb", "fb", T_1020, T_1040,
                        List.of(bare.fallbackAsset), bare.fillerAsset)
                .andExpect(status().isOk());
        playout(bare, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(bare.fillerAsset))
                .andExpect(jsonPath("$.replacedAssetId").value(bare.fallbackAsset))
                .andExpect(jsonPath("$.replacedSource").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.blackoutKey").value(bare.key("fb")));

        // 节目授权撤销 → 原 FALLBACK/GRANT_REVOKED（保底素材），保底也被屏蔽 → SUBSTITUTE。
        // 先撤销插播所用 news 授权，使候选落到已发布节目，再由节目撤销落到保底。
        postJson("/api/grants/" + ctx.newsGrant + "/revoke", Map.of("requestId", req(ctx, "rv-news")))
                .andExpect(status().isOk());
        postJson("/api/grants/" + ctx.movieGrant + "/revoke", Map.of("requestId", req(ctx, "rv-movie")))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.fillerAsset))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.replacedSource").value("FALLBACK"))
                .andExpect(jsonPath("$.publicationId").isNotEmpty())
                .andExpect(jsonPath("$.segmentId").isNotEmpty());
    }

    // ---------- 窗口校验：时长、同日、集合、404、422、409 ----------

    @Test
    void windowValidationErrorBranches() throws Exception {
        Ctx ctx = newContext();

        // 400：结束不大于开始
        createWindow(ctx, "bad-1", "k1", T_1040, T_1020, List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isBadRequest());
        // 400：超过 6 小时（6 小时 1 毫秒）
        createWindow(ctx, "bad-2", "k2", T_14, "2026-09-22T20:00:00.001+08:00",
                        List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isBadRequest());
        // 400：时长恰为 6 小时合法（在下方成功），跨业务日非法
        createWindow(ctx, "bad-3", "k3", "2026-09-22T23:00:00.000+08:00",
                        "2026-09-23T01:00:00.000+08:00",
                        List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isBadRequest());
        // 400：被屏蔽集合为空（Bean Validation）
        mvc.perform(post("/api/blackout-windows").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                "bad-4", ctx.key("k4"), ctx.channel, T_1020, T_1040,
                                List.of(), ctx.fillerAsset))))
                .andExpect(status().isBadRequest());
        // 400：去重后超过 20 个
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(ctx.prefix + "extra" + i);
        }
        createWindow(ctx, "bad-5", "k5", T_1020, T_1040, tooMany, ctx.fillerAsset)
                .andExpect(status().isBadRequest());
        // 400：缺少 requestId
        mvc.perform(post("/api/blackout-windows").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                null, ctx.key("k6"), ctx.channel, T_1020, T_1040,
                                List.of(ctx.movieAsset), ctx.fillerAsset))))
                .andExpect(status().isBadRequest());

        // 失败回滚：非法参数不产生窗口记录
        getWindow(ctx, "k2").andExpect(status().isNotFound());

        // 恰为 6 小时：合法
        createWindow(ctx, "ok-6h", "six", T_14, T_20, List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isOk());

        // 被屏蔽集合含重复项：去重后 2 个（≤20）即合法，明细为去重后的原序集合
        createWindow(ctx, "dup-ok", "dup", "2026-09-22T09:00:00.000+08:00",
                        "2026-09-22T09:30:00.000+08:00",
                        List.of(ctx.movieAsset, ctx.newsAsset, ctx.movieAsset, ctx.newsAsset),
                        ctx.fillerAsset)
                .andExpect(status().isOk());
        getWindow(ctx, "dup")
                .andExpect(jsonPath("$.blockedAssetIds.length()").value(2))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.blockedAssetIds[1]").value(ctx.newsAsset));

        // 404：频道不存在
        createWindowRaw("bwr-" + ctx.prefix + "nf-1", ctx.key("nf1"), ctx.prefix + "ghost", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isNotFound());
        // 404：替补素材不存在
        createWindowRaw("bwr-" + ctx.prefix + "nf-2", ctx.key("nf2"), ctx.channel, T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.prefix + "ghost")
                .andExpect(status().isNotFound());
        // 404：被屏蔽素材不存在
        createWindowRaw("bwr-" + ctx.prefix + "nf-3", ctx.key("nf3"), ctx.channel, T_1020, T_1040,
                        List.of(ctx.prefix + "ghost"), ctx.fillerAsset)
                .andExpect(status().isNotFound());
        // 404：明细/取消不存在
        getWindowRaw(ctx.prefix + "ghost").andExpect(status().isNotFound());
        postJson("/api/blackout-windows/" + ctx.prefix + "ghost/cancel",
                        Map.of("requestId", req(ctx, "nf-4")))
                .andExpect(status().isNotFound());

        // 422：替补出现在被屏蔽集合中
        createWindow(ctx, "u-1", "u1", T_1020, T_1040,
                        List.of(ctx.movieAsset, ctx.fillerAsset), ctx.fillerAsset)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SUBSTITUTE_BLOCKED"));

        // 409：区间重叠
        createWindow(ctx, "ov-a", "a", T_1020, T_1040, List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk());
        createWindow(ctx, "ov-b", "b", "2026-09-22T10:30:00.000+08:00",
                        "2026-09-22T10:50:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.fillerAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BLACKOUT_INTERVAL_CONFLICT"));
        // 端点相接合法
        createWindow(ctx, "ov-c", "c", T_1040,
                        "2026-09-22T10:50:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.fillerAsset)
                .andExpect(status().isOk());
        // 409：重复 blackoutKey
        createWindow(ctx, "ov-d", "a", "2026-09-22T15:00:00.000+08:00",
                        "2026-09-22T15:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_BLACKOUT_KEY"));
    }

    // ---------- 取消即失效、明细保留原集合、区间释放 ----------

    @Test
    void cancelInvalidatesAndPreservesDetail() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);
        createWindow(ctx, "c-req", "cw", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isOk());
        playout(ctx, T_1030).andExpect(jsonPath("$.source").value("SUBSTITUTE"));

        postJson("/api/blackout-windows/" + ctx.key("cw") + "/cancel",
                        Map.of("requestId", req(ctx, "c-cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value(req(ctx, "c-cancel")))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset));

        // 明细保留取消时刻与原集合
        getWindow(ctx, "cw")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value(req(ctx, "c-cancel")))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.substituteAssetId").value(ctx.fillerAsset));

        // 取消后查询回到原逻辑
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 已取消再取消：409
        postJson("/api/blackout-windows/" + ctx.key("cw") + "/cancel",
                        Map.of("requestId", req(ctx, "c-cancel-again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BLACKOUT_NOT_ACTIVE"));

        // 取消提交后区间释放：与已取消窗口重叠可再创建
        createWindow(ctx, "c-req-2", "cw2", T_1030,
                        "2026-09-22T10:35:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 幂等：重放、换序同参、异参 409、失败不占键、不复活 ----------

    @Test
    void idempotencyReplayReorderChangedParamsAndNoResurrection() throws Exception {
        Ctx ctx = newContext();
        String idemReq = "bwr-" + ctx.prefix + "idem-1";

        Map<String, Object> body = windowBody(idemReq, ctx.key("io"), ctx.channel,
                T_1020, T_1040, List.of(ctx.movieAsset, ctx.newsAsset), ctx.fillerAsset);
        postJson("/api/blackout-windows", body).andExpect(status().isOk());

        // 同参重放：首次结果
        postJson("/api/blackout-windows", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("io")));

        // 被屏蔽集合换序：视为同参，返回首次结果，明细顺序保持首次请求顺序
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io"), ctx.channel,
                T_1020, T_1040, List.of(ctx.newsAsset, ctx.movieAsset), ctx.fillerAsset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("io")));
        getWindow(ctx, "io")
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.blockedAssetIds[1]").value(ctx.newsAsset));

        // 异参：换替补 → 409
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io"), ctx.channel,
                T_1020, T_1040, List.of(ctx.movieAsset, ctx.newsAsset), ctx.alertAsset))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 异参：换区间 → 409
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io"), ctx.channel,
                T_1020, "2026-09-22T10:35:00.000+08:00",
                List.of(ctx.movieAsset, ctx.newsAsset), ctx.fillerAsset))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId、不占 blackoutKey：先 422（替补在集合中），再同 requestId 同键成功
        createWindow(ctx, "retry-1", "rk", T_1020, T_1040,
                        List.of(ctx.fillerAsset), ctx.fillerAsset)
                .andExpect(status().isUnprocessableEntity());
        getWindow(ctx, "rk").andExpect(status().isNotFound());
        createWindow(ctx, "retry-1", "rk", "2026-09-22T12:00:00.000+08:00",
                        "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("rk")));

        // 取消幂等：同 requestId 重放返回首次 CANCELLED 明细
        postJson("/api/blackout-windows/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", req(ctx, "cancel-rk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/blackout-windows/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", req(ctx, "cancel-rk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 同 requestId 取消其他窗口：409
        postJson("/api/blackout-windows/" + ctx.key("io") + "/cancel",
                        Map.of("requestId", req(ctx, "cancel-rk")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 重放创建不能复活已取消窗口：新 requestId 同键 → 409，明细仍 CANCELLED
        createWindow(ctx, "resurrect-1", "rk", "2026-09-22T12:00:00.000+08:00",
                        "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_BLACKOUT_KEY"));
        getWindow(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
        // 原创建 requestId 重放只返回历史响应，不改变 CANCELLED 状态
        createWindow(ctx, "retry-1", "rk", "2026-09-22T12:00:00.000+08:00",
                        "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getWindow(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- 并发：重叠窗口最多一条成功；相邻均成功 ----------

    @Test
    void concurrentOverlapOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/blackout-windows";

        List<Integer> overlap = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                "cc-req-" + ctx.prefix + i, ctx.key("cc" + i), ctx.channel,
                                T_1020, T_1040, List.of(ctx.movieAsset),
                                i == 0 ? ctx.fillerAsset : ctx.alertAsset))))
                .andReturn().getResponse().getStatus());
        assertThat(overlap).containsExactlyInAnyOrder(200, 409);

        // 相邻区间并发：均成功
        List<Integer> adjacent = runConcurrently(2, i -> {
            String start = i == 0 ? "2026-09-22T15:00:00.000+08:00" : "2026-09-22T15:15:00.000+08:00";
            String end = i == 0 ? "2026-09-22T15:15:00.000+08:00" : "2026-09-22T15:30:00.000+08:00";
            return mvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(windowBody(
                                    "ad-req-" + ctx.prefix + i, ctx.key("ad" + i), ctx.channel,
                                    start, end, List.of(ctx.movieAsset), ctx.fillerAsset))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(adjacent).containsExactly(200, 200);
        // 最终库内状态：重叠区间只有一条 ACTIVE
        int activeCount = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult result = getWindow(ctx, "cc" + i).andReturn();
            if (result.getResponse().getStatus() == 200
                    && readJson(result).get("status").asText().equals("ACTIVE")) {
                activeCount++;
            }
        }
        assertThat(activeCount).isEqualTo(1);
    }

    // ---------- 并发：取消与查询交错，结果必须基于一致状态 ----------

    @Test
    void concurrentCancelAndQuerySeeConsistentState() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);
        createWindow(ctx, "race-w", "race", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.fillerAsset)
                .andExpect(status().isOk());

        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            Future<Integer> cancelFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postCancel(ctx.key("race"), req(ctx, "race-cancel"));
            });
            List<Future<JsonNode>> queryFutures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                queryFutures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult result = playout(ctx, T_1030).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    return readJson(result);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(cancelFuture.get(30, TimeUnit.SECONDS)).isEqualTo(200);

            // 每个查询结果只能是两种一致状态之一：
            // 取消前 → SUBSTITUTE(filler, blackoutKey=race)；取消提交后 → PROGRAM(movie)
            int substitutes = 0;
            int programs = 0;
            for (Future<JsonNode> future : queryFutures) {
                JsonNode json = future.get(30, TimeUnit.SECONDS);
                String source = json.get("source").asText();
                if ("SUBSTITUTE".equals(source)) {
                    assertThat(json.get("assetId").asText()).isEqualTo(ctx.fillerAsset);
                    assertThat(json.get("blackoutKey").asText()).isEqualTo(ctx.key("race"));
                    assertThat(json.get("replacedAssetId").asText()).isEqualTo(ctx.movieAsset);
                    substitutes++;
                } else {
                    assertThat(source).isEqualTo("PROGRAM");
                    assertThat(json.get("assetId").asText()).isEqualTo(ctx.movieAsset);
                    assertThat(json.get("blackoutKey").isNull()).isTrue();
                    programs++;
                }
            }
            assertThat(substitutes + programs).isEqualTo(threads);        } finally {
            pool.shutdownNow();
        }

        // 取消完成后查询不再命中
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
    }

    // ---------- 并发：创建与查询交错，不会出现 ACTIVE 窗口未生效的半成品状态 ----------

    @Test
    void concurrentCreateAndQuerySeeConsistentState() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);

        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            Future<Integer> createFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/blackout-windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(windowBody(
                                        "bwr-" + ctx.prefix + "race-create", ctx.key("race2"), ctx.channel,
                                        T_1020, T_1040, List.of(ctx.movieAsset), ctx.fillerAsset))))
                        .andReturn().getResponse().getStatus();
            });
            List<Future<JsonNode>> queryFutures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                queryFutures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult result = playout(ctx, T_1030).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    return readJson(result);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(createFuture.get(30, TimeUnit.SECONDS)).isEqualTo(200);

            for (Future<JsonNode> future : queryFutures) {
                JsonNode json = future.get(30, TimeUnit.SECONDS);
                String source = json.get("source").asText();
                assertThat(source).isIn("PROGRAM", "SUBSTITUTE");
                if ("SUBSTITUTE".equals(source)) {
                    // 窗口与其集合同事务提交：命中窗口时集合必然可见且完成替换
                    assertThat(json.get("assetId").asText()).isEqualTo(ctx.fillerAsset);
                    assertThat(json.get("blackoutKey").asText()).isEqualTo(ctx.key("race2"));
                    assertThat(json.get("replacedAssetId").asText()).isEqualTo(ctx.movieAsset);
                } else {
                    assertThat(json.get("assetId").asText()).isEqualTo(ctx.movieAsset);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("race2")));
    }

    // ---------- 屏蔽不改写草稿与发布快照：窗口内重新发布，窗口后回到新节目 ----------

    @Test
    void blackoutDoesNotRewriteDraftOrPublication() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", T_10, T_11);
        createWindow(ctx, "bw", "keep", T_1020, T_1040,
                        List.of(ctx.movieAsset, ctx.newsAsset), ctx.fillerAsset)
                .andExpect(status().isOk());

        // 窗口内整份替换草稿并发布新版本：屏蔽不影响草稿与发布
        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.prefix + "v2");
        segment.put("assetId", ctx.newsAsset);
        segment.put("start", T_10);
        segment.put("end", T_11);
        segments.add(segment);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", "draft-v2-" + ctx.prefix);
        draftBody.put("expectedDraftVersion", 1);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "pub-v2-" + ctx.prefix,
                                "draftVersion", 2, "expectedPublishedVersion", 1))
                .andExpect(status().isOk());

        // 窗口内新节目的 news 仍在屏蔽集合中 → 替补，快照未被改写
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.replacedSource").value("PROGRAM"));
        // 窗口结束后查询回到新节目
        playout(ctx, T_1040)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset));
    }

    // ---------- 测试辅助 ----------

    private final class Ctx {
        private final String prefix = "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
        private String alertAsset;
        private String fillerAsset;
        private long movieGrant;
        private long newsGrant;

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
        ctx.fillerAsset = ctx.prefix + "filler";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.alertAsset, "durationMs", 900000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.fillerAsset, "durationMs", 600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        // 为 tooMany 场景预置 21 个素材
        for (int i = 0; i < 21; i++) {
            postJson("/api/assets", Map.of("id", ctx.prefix + "extra" + i, "durationMs", 1000))
                    .andExpect(status().isOk());
        }
        return ctx;
    }

    private void publishProgram(Ctx ctx, String segmentId, String start, String end) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.prefix + segmentId);
        segment.put("assetId", ctx.movieAsset);
        segment.put("start", start);
        segment.put("end", end);
        segments.add(segment);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", "draft-" + ctx.prefix + segmentId);
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "pub-" + ctx.prefix + segmentId,
                                "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isOk());
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk()).andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions createOverride(Ctx ctx, String requestId, String shortKey, String assetId,
                                         long grantId, int priority, String start, String end)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "bwo-" + ctx.prefix + requestId);
        body.put("overrideKey", ctx.key(shortKey));
        body.put("channelId", ctx.channel);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return postJson("/api/emergency-overrides", body);
    }

    private ResultActions createWindow(Ctx ctx, String requestId, String shortKey, String start,
                                       String end, List<String> blocked, String substitute)
            throws Exception {
        return createWindowRaw("bwr-" + ctx.prefix + requestId, ctx.key(shortKey), ctx.channel,
                start, end, blocked, substitute);
    }

    /** 取消/撤销等裸 JSON 调用使用的唯一 requestId。 */
    private String req(Ctx ctx, String name) {
        return "bwx-" + ctx.prefix + name;
    }

    private ResultActions createWindowRaw(String requestId, String blackoutKey, String channelId,
                                          String start, String end, List<String> blocked,
                                          String substitute) throws Exception {
        return postJson("/api/blackout-windows",
                windowBody(requestId, blackoutKey, channelId, start, end, blocked, substitute));
    }

    private static Map<String, Object> windowBody(String requestId, String blackoutKey,
                                                  String channelId, String start, String end,
                                                  List<String> blocked, String substitute) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("blackoutKey", blackoutKey);
        body.put("channelId", channelId);
        body.put("start", start);
        body.put("end", end);
        body.put("blockedAssetIds", blocked);
        body.put("substituteAssetId", substitute);
        return body;
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout").param("at", at));
    }

    private ResultActions getWindow(Ctx ctx, String shortKey) throws Exception {
        return getWindowRaw(ctx.key(shortKey));
    }

    private ResultActions getWindowRaw(String blackoutKey) throws Exception {
        return mvc.perform(get("/api/blackout-windows/" + blackoutKey));
    }

    private ResultActions getOverride(Ctx ctx, String shortKey) throws Exception {
        return mvc.perform(get("/api/emergency-overrides/" + ctx.key(shortKey)));
    }

    private int postCancel(String blackoutKey, String requestId) throws Exception {
        return postJson("/api/blackout-windows/" + blackoutKey + "/cancel",
                Map.of("requestId", requestId)).andReturn().getResponse().getStatus();
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
