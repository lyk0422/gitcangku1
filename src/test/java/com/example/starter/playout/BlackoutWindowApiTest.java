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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 频道屏蔽窗口 API 端到端测试：窗口校验、替补回退（EMERGENCY/PROGRAM/FALLBACK 三种原来源）、
 * 取消即失效、明细保留、幂等（含换序同参）与并发一致查询。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁、事务回滚与提交顺序均由真实数据库验证。
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
    private static final String T_1010 = "2026-09-22T10:10:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1025 = "2026-09-22T10:25:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1035 = "2026-09-22T10:35:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_1600 = "2026-09-22T16:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：节目被替补、端点边界、取消即失效、明细保留 ----------

    @Test
    void substituteProgramEndBoundaryCancelInvalidatesAndDetailKept() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);

        createWindow(ctx, "w-create", "w1", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("w1")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.substituteAssetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.cancelRequestId").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isEmpty());

        // 窗口外原逻辑不变
        playout(ctx, T_1010)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.blackoutKey").isEmpty());
        // 起点（含）命中：返回替补，随附 blackoutKey、被替换原素材与原来源
        playout(ctx, T_1020)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("w1")))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.replacedSource").value("PROGRAM"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.overrideKey").isEmpty());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset));
        // 终点（不含）不再命中，窗口结束后查询回到原逻辑
        playout(ctx, T_1040)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 取消即失效
        cancelWindow(ctx, "w1", "w-cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value(ctx.rid("w-cancel")))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        // 历史明细保留取消时刻与原集合
        getWindow(ctx, "w1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.blockedAssetIds.length()").value(1))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset))
                .andExpect(jsonPath("$.substituteAssetId").value(ctx.newsAsset));
        // 取消后播出回到原节目
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
    }

    // ---------- 替补回退覆盖 EMERGENCY 与 FALLBACK 原来源；原素材不在集合不替换 ----------

    @Test
    void substituteEmergencyAndFallbackSourcesAndNonBlockedUntouched() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);

        // EMERGENCY 原来源：插播素材 news 被屏蔽，替补为 alert
        createOverride(ctx, "ov-req", "ov1", ctx.newsAsset, ctx.newsGrant, 5, T_1020, T_1040)
                .andExpect(status().isOk());
        createWindow(ctx, "b-req", "bw1", T_1020, T_1040,
                        List.of(ctx.newsAsset), ctx.alertAsset)
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(ctx.alertAsset))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("bw1")))
                .andExpect(jsonPath("$.replacedAssetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.replacedSource").value("EMERGENCY"));

        // 原选中素材不在被屏蔽集合：即使命中窗口也按原逻辑返回
        Ctx other = newContext();
        publishProgram(other, "seg2", T_10, T_11);
        createWindow(other, "b-req2", "bw2", T_1020, T_1040,
                        List.of(other.newsAsset), other.alertAsset)
                .andExpect(status().isOk());
        playout(other, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(other.movieAsset))
                .andExpect(jsonPath("$.blackoutKey").isEmpty());

        // FALLBACK 原来源：无已发布编排，保底素材被屏蔽时由替补接管
        Ctx bare = newContext();
        createWindow(bare, "b-req3", "bw3", T_1020, T_1040,
                        List.of(bare.fallbackAsset), bare.spareAsset)
                .andExpect(status().isOk());
        playout(bare, T_1030)
                .andExpect(jsonPath("$.source").value("SUBSTITUTE"))
                .andExpect(jsonPath("$.assetId").value(bare.spareAsset))
                .andExpect(jsonPath("$.blackoutKey").value(bare.key("bw3")))
                .andExpect(jsonPath("$.replacedAssetId").value(bare.fallbackAsset))
                .andExpect(jsonPath("$.replacedSource").value("FALLBACK"));
        // 窗口外仍是保底
        playout(bare, T_1010)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(bare.fallbackAsset));
    }

    // ---------- 窗口校验：时长、同日、集合规则、去重、404、422、409 ----------

    @Test
    void validationBranches() throws Exception {
        Ctx ctx = newContext();

        // 400：结束不大于开始
        createWindow(ctx, "bad-1", "k1", T_1030, T_1020,
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isBadRequest());
        // 400：时长超过 6 小时（6 小时 1 毫秒）
        createWindowRaw(ctx.rid("bad-2"), ctx.key("k2"), ctx.channel, T_10,
                        "2026-09-22T16:00:00.001+08:00",
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isBadRequest());
        // 400：跨业务日
        createWindow(ctx, "bad-3", "k3",
                        "2026-09-22T23:00:00.000+08:00",
                        "2026-09-23T01:00:00.000+08:00",
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isBadRequest());
        // 400：被屏蔽集合为空（Bean Validation）
        mvc.perform(post("/api/blackout-windows").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                ctx.rid("bad-4"), ctx.key("k4"), ctx.channel, T_1020, T_1040,
                                List.of(), ctx.newsAsset))))
                .andExpect(status().isBadRequest());
        // 400：被屏蔽集合超过 20 个
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String assetId = ctx.prefix + "many" + i;
            postJson("/api/assets", Map.of("id", assetId, "durationMs", 1000))
                    .andExpect(status().isOk());
            tooMany.add(assetId);
        }
        createWindowRaw(ctx.rid("bad-5"), ctx.key("k5"), ctx.channel, T_1020, T_1040,
                        tooMany, ctx.newsAsset)
                .andExpect(status().isBadRequest());
        // 400：缺少 requestId
        mvc.perform(post("/api/blackout-windows").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                null, ctx.key("k6"), ctx.channel, T_1020, T_1040,
                                List.of(ctx.movieAsset), ctx.newsAsset))))
                .andExpect(status().isBadRequest());

        // 去重：集合内重复素材视为同参去重，明细只保留 1 个
        createWindowRaw(ctx.rid("dup-set"), ctx.key("k7"), ctx.channel, T_1020, T_1040,
                        List.of(ctx.movieAsset, ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedAssetIds.length()").value(1));

        // 422：替补素材出现在被屏蔽集合中
        createWindowRaw(ctx.rid("bad-sub"), ctx.key("k8"), ctx.channel,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.alertAsset, ctx.newsAsset), ctx.newsAsset)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SUBSTITUTE_IN_BLOCKED_SET"));

        // 404：频道不存在
        createWindowRaw(ctx.rid("nf-1"), ctx.key("nf1"), ctx.prefix + "ghost", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isNotFound());
        // 404：被屏蔽素材不存在
        createWindowRaw(ctx.rid("nf-2"), ctx.key("nf2"), ctx.channel,
                        "2026-09-22T13:00:00.000+08:00", "2026-09-22T13:10:00.000+08:00",
                        List.of(ctx.prefix + "ghost"), ctx.newsAsset)
                .andExpect(status().isNotFound());
        // 404：替补素材不存在
        createWindowRaw(ctx.rid("nf-3"), ctx.key("nf3"), ctx.channel,
                        "2026-09-22T13:20:00.000+08:00", "2026-09-22T13:30:00.000+08:00",
                        List.of(ctx.movieAsset), ctx.prefix + "ghost")
                .andExpect(status().isNotFound());
        // 404：明细不存在
        getWindowRaw(ctx.prefix + "ghost").andExpect(status().isNotFound());
        postJson("/api/blackout-windows/" + ctx.prefix + "ghost/cancel",
                        Map.of("requestId", ctx.rid("nf-4")))
                .andExpect(status().isNotFound());

        // 409：重复 blackoutKey
        createWindow(ctx, "dup-key", "k7",
                        "2026-09-22T14:00:00.000+08:00", "2026-09-22T14:10:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.newsAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_BLACKOUT_KEY"));
        // 409：同频道 ACTIVE 窗口重叠（内部重叠）
        createWindowRaw(ctx.rid("ov-1"), ctx.key("ov1"), ctx.channel,
                        "2026-09-22T15:00:00.000+08:00", "2026-09-22T15:30:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.newsAsset)
                .andExpect(status().isOk());
        createWindowRaw(ctx.rid("ov-2"), ctx.key("ov2"), ctx.channel,
                        "2026-09-22T15:20:00.000+08:00", "2026-09-22T15:40:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.newsAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BLACKOUT_INTERVAL_CONFLICT"));
        // 端点相接合法
        createWindowRaw(ctx.rid("ov-3"), ctx.key("ov3"), ctx.channel,
                        "2026-09-22T15:30:00.000+08:00", "2026-09-22T15:50:00.000+08:00",
                        List.of(ctx.alertAsset), ctx.newsAsset)
                .andExpect(status().isOk());

        // 409：取消已取消窗口
        postJson("/api/blackout-windows/" + ctx.key("ov1") + "/cancel",
                        Map.of("requestId", ctx.rid("cancel-ov1")))
                .andExpect(status().isOk());
        postJson("/api/blackout-windows/" + ctx.key("ov1") + "/cancel",
                        Map.of("requestId", ctx.rid("cancel-ov1-again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BLACKOUT_NOT_ACTIVE"));

        // 失败回滚不产生窗口记录
        getWindow(ctx, "k8").andExpect(status().isNotFound());

        // 恰为 6 小时：合法（独立频道避免与上面的窗口区间重叠）
        Ctx sixCtx = newContext();
        createWindow(sixCtx, "six-hours", "six", T_10, T_1600,
                        List.of(sixCtx.movieAsset), sixCtx.newsAsset)
                .andExpect(status().isOk());
    }

    // ---------- 幂等：同参/换序重放、异参 409、失败不占键、不复活 ----------

    @Test
    void idempotencyReplayOrderInsensitiveChangedParamsAndNoResurrection() throws Exception {
        Ctx ctx = newContext();
        String idemReq = ctx.rid("idem-1");
        String retryReq = ctx.rid("retry-1");
        String cancelReq = ctx.rid("cancel-rk");

        // 首次成功；响应中集合按稳定顺序返回
        Map<String, Object> body = windowBody(idemReq, ctx.key("io"), ctx.channel,
                T_1020, T_1040, List.of(ctx.newsAsset, ctx.movieAsset), ctx.alertAsset);
        postJson("/api/blackout-windows", body).andExpect(status().isOk());
        // 同 requestId 同参数重放：首次结果
        postJson("/api/blackout-windows", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("io")))
                .andExpect(jsonPath("$.blockedAssetIds.length()").value(2));
        // 被屏蔽集合换序视为同参：重放成功返回首次结果
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io"), ctx.channel,
                        T_1020, T_1040, List.of(ctx.movieAsset, ctx.newsAsset), ctx.alertAsset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("io")));
        // 异参（更换替补素材）：409
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io"), ctx.channel,
                        T_1020, T_1040, List.of(ctx.movieAsset, ctx.newsAsset), ctx.spareAsset))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 异参（更换键）：409
        postJson("/api/blackout-windows", windowBody(idemReq, ctx.key("io2"), ctx.channel,
                        T_1020, T_1040, List.of(ctx.newsAsset, ctx.movieAsset), ctx.alertAsset))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId、不占 blackoutKey：先 422（替补在集合内），同参改合法后成功
        createWindowRaw(retryReq, ctx.key("rk"), ctx.channel,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.newsAsset)
                .andExpect(status().isUnprocessableEntity());
        getWindow(ctx, "rk").andExpect(status().isNotFound());
        createWindowRaw(retryReq, ctx.key("rk"), ctx.channel,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.alertAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.key("rk")));

        // 取消幂等：同 requestId 重放返回首次的 CANCELLED 明细
        postJson("/api/blackout-windows/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", cancelReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/blackout-windows/" + ctx.key("rk") + "/cancel",
                        Map.of("requestId", cancelReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 同 requestId 用于取消其他窗口：409
        postJson("/api/blackout-windows/" + ctx.key("io") + "/cancel",
                        Map.of("requestId", cancelReq))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 重放创建不得复活已取消窗口
        createWindow(ctx, "resurrect-1", "rk",
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.alertAsset)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_BLACKOUT_KEY"));
        getWindow(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
        // 原创建 requestId 重放只返回历史响应，不改变 CANCELLED 状态
        createWindowRaw(retryReq, ctx.key("rk"), ctx.channel,
                        "2026-09-22T12:00:00.000+08:00", "2026-09-22T12:10:00.000+08:00",
                        List.of(ctx.newsAsset), ctx.alertAsset)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getWindow(ctx, "rk").andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- 并发：重叠最多一条成功、相邻均成功 ----------

    @Test
    void concurrentOverlapOnlyOneWinsAndAdjacentBothSucceed() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/blackout-windows";

        List<Integer> overlap = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(windowBody(
                                "cc-req-" + ctx.prefix + i, ctx.key("cc" + i), ctx.channel,
                                T_1020, T_1040, List.of(ctx.movieAsset), ctx.newsAsset))))
                .andReturn().getResponse().getStatus());
        assertThat(overlap).containsExactlyInAnyOrder(200, 409);

        List<Integer> adjacent = runConcurrently(2, i -> {
            String start = i == 0 ? "2026-09-22T15:00:00.000+08:00" : "2026-09-22T15:15:00.000+08:00";
            String end = i == 0 ? "2026-09-22T15:15:00.000+08:00" : "2026-09-22T15:30:00.000+08:00";
            return mvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(windowBody(
                                    "ad-req-" + ctx.prefix + i, ctx.key("ad" + i), ctx.channel,
                                    start, end, List.of(ctx.movieAsset), ctx.newsAsset))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(adjacent).containsExactly(200, 200);

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

    // ---------- 并发：取消与查询交错，不得出现已取消窗口仍生效，最终状态一致 ----------

    @Test
    void concurrentCancelAndQueryStayConsistent() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);
        createWindow(ctx, "race-create", "race", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isOk());

        int readers = 4;
        CountDownLatch ready = new CountDownLatch(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger substituteSeen = new AtomicInteger();
        AtomicInteger programSeen = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < readers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int j = 0; j < 50; j++) {
                            MvcResult result = playout(ctx, T_1030).andReturn();
                            JsonNode json = readJson(result);
                            String source = json.get("source").asText();
                            if ("SUBSTITUTE".equals(source)) {
                                substituteSeen.incrementAndGet();
                            } else if ("PROGRAM".equals(source)) {
                                programSeen.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    Thread.sleep(5);
                    postJson("/api/blackout-windows/" + ctx.key("race") + "/cancel",
                            Map.of("requestId", ctx.rid("race-cancel"))).andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两种状态都可能被观察到（按提交顺序裁决），但取消提交后不得再观察到替补
        assertThat(substituteSeen.get() + programSeen.get()).isGreaterThan(0);
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
        getWindow(ctx, "race")
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.blockedAssetIds[0]").value(ctx.movieAsset));
    }

    // ---------- 屏蔽不影响插播、发布等既有写入 ----------

    @Test
    void blackoutDoesNotChangeOverridesOrPublication() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_10, T_11);
        createWindow(ctx, "keep", "keep", T_1020, T_1040,
                        List.of(ctx.movieAsset), ctx.newsAsset)
                .andExpect(status().isOk());

        // 窗口期间仍可创建插播；插播素材未被屏蔽时照常 EMERGENCY
        createOverride(ctx, "ov-req", "ov", ctx.alertAsset, ctx.alertGrant, 5, T_1025, T_1035)
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.alertAsset));
        // 插播记录不被屏蔽改写
        getOverride(ctx, "ov")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.alertAsset));
    }

    // ---------- 测试辅助 ----------

    private final class Ctx {
        private final String prefix = "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
        private String alertAsset;
        private String spareAsset;
        private long movieGrant;
        private long newsGrant;
        private long alertGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }

        /** 生成上下文唯一的幂等 requestId，避免跨测试共享 H2 库时主键冲突。 */
        private String rid(String requestId) {
            return "req-" + prefix + requestId;
        }
    }

    private ResultActions cancelWindow(Ctx ctx, String shortKey, String requestId) throws Exception {
        return postJson("/api/blackout-windows/" + ctx.key(shortKey) + "/cancel",
                Map.of("requestId", ctx.rid(requestId)));
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.alertAsset = ctx.prefix + "alert";
        ctx.spareAsset = ctx.prefix + "spare";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.alertAsset, "durationMs", 900000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.spareAsset, "durationMs", 600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        ctx.alertGrant = createGrant(ctx.channel, ctx.alertAsset, GRANT_FROM, GRANT_TO);
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
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions createOverride(Ctx ctx, String requestId, String shortKey, String assetId,
                                         long grantId, int priority, String start, String end)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", ctx.rid(requestId));
        body.put("overrideKey", ctx.key(shortKey));
        body.put("channelId", ctx.channel);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return postJson("/api/emergency-overrides", body);
    }

    private ResultActions createWindow(Ctx ctx, String requestId, String shortKey,
                                       String start, String end,
                                       List<String> blockedAssetIds, String substituteAssetId)
            throws Exception {
        return createWindowRaw(ctx.rid(requestId), ctx.key(shortKey), ctx.channel,
                start, end, blockedAssetIds, substituteAssetId);
    }

    private ResultActions createWindowRaw(String requestId, String blackoutKey, String channelId,
                                          String start, String end,
                                          List<String> blockedAssetIds, String substituteAssetId)
            throws Exception {
        return postJson("/api/blackout-windows", windowBody(requestId, blackoutKey, channelId,
                start, end, blockedAssetIds, substituteAssetId));
    }

    private static Map<String, Object> windowBody(String requestId, String blackoutKey,
                                                  String channelId, String start, String end,
                                                  List<String> blockedAssetIds,
                                                  String substituteAssetId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("blackoutKey", blackoutKey);
        body.put("channelId", channelId);
        body.put("start", start);
        body.put("end", end);
        body.put("blockedAssetIds", blockedAssetIds);
        body.put("substituteAssetId", substituteAssetId);
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
