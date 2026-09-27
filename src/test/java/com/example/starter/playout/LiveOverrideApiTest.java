package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 直播覆盖 API 端到端测试：主流程（创建/命中/恢复/结算错过）、失败分支（409/422/404/400）、
 * 跨频道原子性、幂等重放、并发裁决与状态边界。运行环境为独立命名的 H2（MODE=MySQL）内存库，
 * 唯一约束、行锁与事务提交顺序均由真实数据库验证；业务时间由可控时钟驱动，不依赖真实等待。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:playout_live;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveOverrideApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String BASE_NOW = "2026-09-22T08:00:00.000+08:00";
    private static final String T_1000 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_1015 = "2026-09-22T10:15:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1045 = "2026-09-22T10:45:00.000+08:00";
    private static final String T_1100 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_1115 = "2026-09-22T11:15:00.000+08:00";
    private static final String T_1130 = "2026-09-22T11:30:00.000+08:00";
    private static final String T_1200 = "2026-09-22T12:00:00.000+08:00";
    private static final String T_1300 = "2026-09-22T13:00:00.000+08:00";

    /** 可控时钟：测试用 @Primary 替换系统时钟，直接设定业务当前时间。 */
    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutablePlayoutClock mutablePlayoutClock() {
            return new MutablePlayoutClock();
        }
    }

    static final class MutablePlayoutClock implements PlayoutClock {
        private final AtomicLong nowMs = new AtomicLong();

        void set(String iso) {
            nowMs.set(OffsetDateTime.parse(iso).toInstant().toEpochMilli());
        }

        @Override
        public long nowMs() {
            return nowMs.get();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutablePlayoutClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(BASE_NOW);
    }

    // ---------- 主流程：创建、命中、结束后恢复未开始素材、结算写 MISSED_BY_LIVE ----------

    @Test
    void createHitResumeSettleAndMissedFlow() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);

        // 覆盖前：10:15 播出原节目
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 创建覆盖令 10:00-11:00：ACTIVE，两段受影响素材 PENDING，授权版本快照入响应
        createLive(ctx, "req-c1", "op-a", "lo-1", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("lo-1")))
                .andExpect(jsonPath("$.operator").value("op-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channels.length()").value(1))
                .andExpect(jsonPath("$.channels[0].grantId").value(ctx.liveGrant))
                .andExpect(jsonPath("$.channels[0].grantValidFrom").value(GRANT_FROM))
                .andExpect(jsonPath("$.affectedSegments.length()").value(2))
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("PENDING"))
                .andExpect(jsonPath("$.affectedSegments[1].disposition").value("PENDING"))
                .andExpect(jsonPath("$.revokeRequestId").isEmpty())
                .andExpect(jsonPath("$.settleRequestId").isEmpty());

        // 覆盖内：直播源替代原节目，原编排保留占位
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.liveAsset))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("lo-1")))
                .andExpect(jsonPath("$.publicationId").isEmpty());
        playout(ctx, T_1045)
                .andExpect(jsonPath("$.source").value("LIVE"));
        // 覆盖结束后：未开始的 11:00-11:30 片段恢复播出
        playout(ctx, T_1115)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 覆盖结束后结算：状态 SETTLED，受影响素材写入 MISSED_BY_LIVE
        clock.set(T_1100);
        settleLive(ctx, "req-s1", "op-a", "lo-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settleRequestId").value("req-s1"))
                .andExpect(jsonPath("$.settledAt").isNotEmpty())
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("MISSED_BY_LIVE"))
                .andExpect(jsonPath("$.affectedSegments[1].disposition").value("MISSED_BY_LIVE"));

        // 已错过素材不自动补播：历史时刻查询返回 MISSED_BY_LIVE 保底
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("MISSED_BY_LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset));
        // 未开始的片段不受影响
        playout(ctx, T_1115)
                .andExpect(jsonPath("$.source").value("PROGRAM"));

        // 明细：恢复结果不可变地保留
        getLive(ctx, "lo-1")
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("MISSED_BY_LIVE"));

        // 诊断：数量与时长的实际值
        getDiagnostics(ctx, "lo-1")
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.channelCount").value(1))
                .andExpect(jsonPath("$.overrideDurationMs").value(3600000))
                .andExpect(jsonPath("$.affectedSegmentCount").value(2))
                .andExpect(jsonPath("$.missedCount").value(2))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.restoredCount").value(0))
                .andExpect(jsonPath("$.coveredProgramDurationMs").value(3600000))
                .andExpect(jsonPath("$.channels[0].channelId").value(ctx.channel))
                .andExpect(jsonPath("$.channels[0].affectedSegmentCount").value(2));

        // 历史：稳定排序包含该覆盖令
        getHistory(ctx)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].overrideKey").value(ctx.key("lo-1")))
                .andExpect(jsonPath("$[0].status").value("SETTLED"))
                .andExpect(jsonPath("$[0].channelIds[0]").value(ctx.channel));
    }

    // ---------- 撤销：仅未开始可撤销，撤销恢复全部占位 ----------

    @Test
    void revokeBeforeStartRestoresSegments() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "rb-c", "op", "lo-r", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());

        revokeLive(ctx, "rb-rv", "op", "lo-r")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value("rb-rv"))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty())
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("RESTORED"))
                .andExpect(jsonPath("$.affectedSegments[1].disposition").value("RESTORED"));

        // 撤销后覆盖时段内恢复原节目
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 重复撤销：409 状态冲突
        revokeLive(ctx, "rb-rv-2", "op", "lo-r")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_NOT_ACTIVE"));
        // 已撤销覆盖令不能结算
        clock.set(T_1100);
        settleLive(ctx, "rb-st", "op", "lo-r")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_NOT_ACTIVE"));
        // 明细保持 REVOKED / RESTORED，不被后续操作改写
        getLive(ctx, "lo-r")
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("RESTORED"));
    }

    @Test
    void revokeAfterStartRejected() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "ra-c", "op", "lo-s", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());

        // 覆盖已开始：撤销 409，响应给出实际时间与要求边界
        clock.set("2026-09-22T10:05:00.000+08:00");
        revokeLive(ctx, "ra-rv", "op", "lo-s")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_ALREADY_STARTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已超过")));
        // 状态保持 ACTIVE
        getLive(ctx, "lo-s").andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void settleBeforeEndRejected() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "sb-c", "op", "lo-e", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());

        // 覆盖未结束：结算 409，响应给出剩余时长差额
        clock.set(T_1030);
        settleLive(ctx, "sb-st", "op", "lo-e")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_NOT_ENDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("剩余")));
        getLive(ctx, "lo-e").andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 区间规则：重叠 409、相邻合法、跨频道独立 ----------

    @Test
    void overlapConflictAdjacentAndCrossChannelAllowed() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);

        createLive(ctx, "r1", "op", "lo-a", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());
        // 同频道重叠：409
        createLive(ctx, "r2", "op", "lo-b", ctx.liveAsset, ctx.liveGrant, T_1030, T_1130)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));
        // 同频道相邻（左闭右开）：合法
        createLive(ctx, "r3", "op", "lo-c", ctx.liveAsset, ctx.liveGrant, T_1100, T_1200)
                .andExpect(status().isOk());

        // 其他频道同时段：互不影响
        Ctx other = newContext();
        long sharedGrant = createGrant(other.channel, ctx.liveAsset, GRANT_FROM, GRANT_TO);
        createLiveRaw("r4", "op", other.key("lo-x"), ctx.liveAsset, T_1000, T_1100,
                        List.of(channelEntry(other.channel, sharedGrant)))
                .andExpect(status().isOk());

        // 历史按开始时间稳定排序：lo-a(10:00) 在 lo-c(11:00) 前
        getHistory(ctx)
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].overrideKey").value(ctx.key("lo-a")))
                .andExpect(jsonPath("$[1].overrideKey").value(ctx.key("lo-c")));
    }

    // ---------- 404 / 400 / 422 失败分支 ----------

    @Test
    void validationAndNotFoundBranches() throws Exception {
        Ctx ctx = newContext();

        // 404：直播源素材不存在
        createLive(ctx, "nf-1", "op", "k1", ctx.prefix + "ghost", ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isNotFound());
        // 404：频道不存在
        createLiveRaw("nf-2", "op", ctx.key("k2"), ctx.liveAsset, T_1000, T_1100,
                        List.of(channelEntry(ctx.prefix + "ghost", ctx.liveGrant)))
                .andExpect(status().isNotFound());
        // 404：授权不存在
        createLiveRaw("nf-3", "op", ctx.key("k3"), ctx.liveAsset, T_1000, T_1100,
                        List.of(channelEntry(ctx.channel, 999999)))
                .andExpect(status().isNotFound());
        // 404：明细 / 诊断 / 撤销 / 结算目标不存在
        getLive(ctx, "ghost").andExpect(status().isNotFound());
        mvc.perform(get("/api/live-overrides/" + ctx.key("ghost") + "/diagnostics"))
                .andExpect(status().isNotFound());
        postJson("/api/live-overrides/" + ctx.key("ghost") + "/revoke",
                        Map.of("requestId", "nf-4", "operator", "op"))
                .andExpect(status().isNotFound());
        postJson("/api/live-overrides/" + ctx.key("ghost") + "/settle",
                        Map.of("requestId", "nf-5", "operator", "op"))
                .andExpect(status().isNotFound());
        // 404：历史查询频道不存在
        mvc.perform(get("/api/channels/" + ctx.prefix + "ghost/live-overrides"))
                .andExpect(status().isNotFound());

        // 400：结束不大于开始，消息含实际时长与要求
        createLive(ctx, "bd-1", "op", "k4", ctx.liveAsset, ctx.liveGrant, T_1100, T_1000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("实际时长")));
        // 400：频道集合重复
        createLiveRaw("bd-2", "op", ctx.key("k5"), ctx.liveAsset, T_1000, T_1100,
                        List.of(channelEntry(ctx.channel, ctx.liveGrant),
                                channelEntry(ctx.channel, ctx.liveGrant)))
                .andExpect(status().isBadRequest());
        // 400：缺少 operator / requestId / channels（Bean Validation）
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(liveBody(
                                "bd-3", null, ctx.key("k6"), ctx.liveAsset, T_1000, T_1100,
                                List.of(channelEntry(ctx.channel, ctx.liveGrant))))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(liveBody(
                                null, "op", ctx.key("k7"), ctx.liveAsset, T_1000, T_1100,
                                List.of(channelEntry(ctx.channel, ctx.liveGrant))))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/live-overrides").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(liveBody(
                                "bd-4", "op", ctx.key("k8"), ctx.liveAsset, T_1000, T_1100,
                                List.of()))))
                .andExpect(status().isBadRequest());

        // 422：授权素材不匹配（movie 授权用于 live 素材）
        createLive(ctx, "un-1", "op", "k9", ctx.liveAsset, ctx.movieGrant, T_1000, T_1100)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));
        // 422：授权不属于该频道
        Ctx other = newContext();
        createLive(ctx, "un-2", "op", "k10", ctx.liveAsset, other.liveGrant, T_1000, T_1100)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));
        // 422：授权已撤销
        long revokedGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        postJson("/api/grants/" + revokedGrant + "/revoke", Map.of("requestId", "rv-g"))
                .andExpect(status().isOk());
        createLive(ctx, "un-3", "op", "k11", ctx.newsAsset, revokedGrant, T_1000, T_1100)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));
        // 422：授权未完整覆盖区间，消息含缺口差额
        long shortGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, T_1030);
        createLive(ctx, "un-4", "op", "k12", ctx.newsAsset, shortGrant, T_1000, T_1100)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("终点缺口 1800000ms")));
        // 422：保底素材不得作为直播源
        long fallbackGrantHolder = ctx.liveGrant;
        createLive(ctx, "un-5", "op", "k13", ctx.fallbackAsset, fallbackGrantHolder, T_1000, T_1100)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));

        // 失败不留覆盖令
        getLive(ctx, "k12").andExpect(status().isNotFound());
    }

    // ---------- 与已开始节目冲突：409 ----------

    @Test
    void startedProgramConflict() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);

        // 节目 10:00-10:30 已开始（当前 10:15）：覆盖 10:10-11:00 冲突 409
        clock.set(T_1015);
        createLive(ctx, "sp-1", "op", "lo-p1", ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T10:10:00.000+08:00", T_1100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_PROGRAM_STARTED"));
        // 不覆盖已开始片段的区间合法
        createLive(ctx, "sp-2", "op", "lo-p2", ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T10:35:00.000+08:00", T_1100)
                .andExpect(status().isOk());
    }

    // ---------- 跨频道创建：全量校验，任一失败不留部分覆盖 ----------

    @Test
    void crossChannelCreateIsAtomic() throws Exception {
        Ctx a = newContext();
        Ctx b = newContext();
        publishProgram(a);
        publishProgram(b);
        // 共享直播源，两个频道各自授权
        String sharedLive = a.prefix + "shared-live";
        postJson("/api/assets", Map.of("id", sharedLive, "durationMs", 7200000))
                .andExpect(status().isOk());
        long grantA = createGrant(a.channel, sharedLive, GRANT_FROM, GRANT_TO);
        long grantB = createGrant(b.channel, sharedLive, GRANT_FROM, GRANT_TO);

        // B 频道已有 ACTIVE 覆盖 10:00-11:00
        createLiveRaw("b-pre", "op", b.key("pre"), sharedLive, T_1000, T_1100,
                        List.of(channelEntry(b.channel, grantB)))
                .andExpect(status().isOk());

        // 跨频道创建 [A, B]：B 重叠失败 → 整体 409，A 不留部分覆盖
        createLiveRaw("x-1", "op", a.key("xlo"), sharedLive, T_1000, T_1100,
                        List.of(channelEntry(a.channel, grantA), channelEntry(b.channel, grantB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));
        getLiveRaw(a.key("xlo")).andExpect(status().isNotFound());
        mvc.perform(get("/api/channels/" + a.channel + "/live-overrides"))
                .andExpect(jsonPath("$.length()").value(0));

        // 失败不占 requestId：同 requestId 仅含 A 频道重试成功
        createLiveRaw("x-1", "op", a.key("xlo"), sharedLive, T_1000, T_1100,
                        List.of(channelEntry(a.channel, grantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(1));

        // 双频道均合法时一次创建成功，各自记录受影响素材
        createLiveRaw("x-2", "op", a.key("xlo2"), sharedLive, T_1100, T_1200,
                        List.of(channelEntry(a.channel, grantA), channelEntry(b.channel, grantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels.length()").value(2))
                .andExpect(jsonPath("$.affectedSegments.length()").value(2));
    }

    // ---------- 幂等：同参重放、异参 409、失败不占键、重放不改状态 ----------

    @Test
    void idempotencyReplayChangedParamsAndFailure() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);

        Map<String, Object> body = liveBody("idem-1", "op-a", ctx.key("lo-i"), ctx.liveAsset,
                T_1000, T_1100, List.of(channelEntry(ctx.channel, ctx.liveGrant)));
        MvcResult first = postJson("/api/live-overrides", body)
                .andExpect(status().isOk()).andReturn();
        // 同 requestId 同参数重放：返回首次完整响应快照
        postJson("/api/live-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("lo-i")));
        // 同 requestId 改操作者：409（操作者计入幂等指纹）
        postJson("/api/live-overrides", liveBody("idem-1", "op-b", ctx.key("lo-i"), ctx.liveAsset,
                        T_1000, T_1100, List.of(channelEntry(ctx.channel, ctx.liveGrant))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改区间：409
        postJson("/api/live-overrides", liveBody("idem-1", "op-a", ctx.key("lo-i"), ctx.liveAsset,
                        T_1000, T_1115, List.of(channelEntry(ctx.channel, ctx.liveGrant))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId：先 422，再同 requestId 成功（区间 11:00-12:00 与 lo-i 相邻不重叠）
        createLive(ctx, "idem-2", "op", "lo-j", ctx.liveAsset, ctx.movieGrant, T_1100, T_1200)
                .andExpect(status().isUnprocessableEntity());
        createLive(ctx, "idem-2", "op", "lo-j", ctx.liveAsset, ctx.liveGrant, T_1100, T_1200)
                .andExpect(status().isOk());

        // 撤销幂等：同 requestId 重放返回首次 CANCELLED 等价快照（REVOKED）
        revokeLive(ctx, "idem-rv", "op", "lo-j")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        revokeLive(ctx, "idem-rv", "op", "lo-j")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 同 requestId 改操作者：409
        revokeLive(ctx, "idem-rv", "op2", "lo-j")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 结算幂等：先推进时钟到结束后（区间 12:00-13:00 与 lo-j 相邻不重叠）
        createLive(ctx, "idem-3", "op", "lo-k", ctx.liveAsset, ctx.liveGrant, T_1200, T_1300)
                .andExpect(status().isOk());
        clock.set(T_1300);
        settleLive(ctx, "idem-st", "op", "lo-k")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        settleLive(ctx, "idem-st", "op", "lo-k")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));

        // 创建快照重放不改写现状：lo-i 已 ACTIVE，撤销后重放创建仍返回首次 ACTIVE 快照，
        // 但明细保持 REVOKED（读取不改状态）
        clock.set(BASE_NOW);
        revokeLive(ctx, "idem-rv-i", "op", "lo-i").andExpect(status().isOk());
        MvcResult replay = postJson("/api/live-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        getLive(ctx, "lo-i").andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void duplicateKeyAndNoResurrection() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "d-1", "op", "lo-d", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());
        // 新 requestId 同键再创建：409
        createLive(ctx, "d-2", "op", "lo-d", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_OVERRIDE_KEY"));
        // 撤销后同键仍不可复活
        revokeLive(ctx, "d-3", "op", "lo-d").andExpect(status().isOk());
        createLive(ctx, "d-4", "op", "lo-d", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_OVERRIDE_KEY"));
        getLive(ctx, "lo-d").andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ---------- 播出优先级：紧急插播 > 直播覆盖 > 节目；直播源授权撤销后错过不补播 ----------

    @Test
    void emergencyBeatsLiveAndLiveBeatsProgram() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "p-1", "op", "lo-p", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());
        // 紧急插播 10:20-10:40
        postJson("/api/emergency-overrides", emergencyBody("p-2", ctx.key("em-1"), ctx.channel,
                        ctx.newsAsset, ctx.newsGrant, 5,
                        "2026-09-22T10:20:00.000+08:00", "2026-09-22T10:40:00.000+08:00"))
                .andExpect(status().isOk());

        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.liveAsset));
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset));
        playout(ctx, T_1045)
                .andExpect(jsonPath("$.source").value("LIVE"));
    }

    @Test
    void liveGrantRevokedMidOverrideFallsBackMissed() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "g-1", "op", "lo-g", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());

        // 直播源授权被撤销：覆盖令与授权版本快照不改写
        postJson("/api/grants/" + ctx.liveGrant + "/revoke", Map.of("requestId", "g-2"))
                .andExpect(status().isOk());
        getLive(ctx, "lo-g")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channels[0].grantId").value(ctx.liveGrant))
                .andExpect(jsonPath("$.channels[0].grantValidFrom").value(GRANT_FROM));

        // 覆盖内不再命中 LIVE，原节目已被覆盖错过：MISSED_BY_LIVE 保底
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("MISSED_BY_LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset));
    }

    @Test
    void liveOverrideWithoutPublication() throws Exception {
        Ctx ctx = newContext();
        // 无发布编排的频道：覆盖内 LIVE，覆盖外 NO_PUBLISHED_SCHEDULE 保底
        createLive(ctx, "n-1", "op", "lo-n", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedSegments.length()").value(0));
        playout(ctx, T_1015)
                .andExpect(jsonPath("$.source").value("LIVE"));
        playout(ctx, T_1115)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        getDiagnostics(ctx, "lo-n")
                .andExpect(jsonPath("$.affectedSegmentCount").value(0))
                .andExpect(jsonPath("$.coveredProgramDurationMs").value(0));
    }

    // ---------- 并发：同频道重叠仅一条成功；并发结算按提交顺序裁决 ----------

    @Test
    void concurrentOverlappingCreateOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);

        List<Integer> statuses = runConcurrently(2, i -> postJson("/api/live-overrides",
                        liveBody("cc-req-" + ctx.prefix + i, "op", ctx.key("cc" + i),
                                ctx.liveAsset, T_1000, T_1100,
                                List.of(channelEntry(ctx.channel, ctx.liveGrant))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终库内状态：仅一条 ACTIVE 覆盖
        getHistory(ctx).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void concurrentSettleFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "cs-0", "op", "lo-cs", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());
        clock.set(T_1100);

        // 两个不同 requestId 并发结算：恰好一个成功，最终状态 SETTLED 且恢复结果一致
        List<Integer> statuses = runConcurrently(2, i -> postJson(
                        "/api/live-overrides/" + ctx.key("lo-cs") + "/settle",
                        Map.of("requestId", "cs-req-" + ctx.prefix + i, "operator", "op"))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        getLive(ctx, "lo-cs")
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.affectedSegments[0].disposition").value("MISSED_BY_LIVE"))
                .andExpect(jsonPath("$.affectedSegments[1].disposition").value("MISSED_BY_LIVE"));
    }

    // ---------- 只读查询不改变状态 ----------

    @Test
    void readsDoNotChangeState() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx);
        createLive(ctx, "ro-1", "op", "lo-ro", ctx.liveAsset, ctx.liveGrant, T_1000, T_1100)
                .andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            getLive(ctx, "lo-ro").andExpect(jsonPath("$.status").value("ACTIVE"));
            getDiagnostics(ctx, "lo-ro")
                    .andExpect(jsonPath("$.pendingCount").value(2))
                    .andExpect(jsonPath("$.missedCount").value(0));
            getHistory(ctx).andExpect(jsonPath("$.length()").value(1));
            playout(ctx, T_1015).andExpect(jsonPath("$.source").value("LIVE"));
        }
        // 读取之后撤销仍可正常推进状态
        revokeLive(ctx, "ro-2", "op", "lo-ro")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、普通素材 movie/news、直播源素材 live、频道及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
        private String liveAsset;
        private long movieGrant;
        private long newsGrant;
        private long liveGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.liveAsset = ctx.prefix + "live";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.liveAsset, "durationMs", 7200000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        ctx.liveGrant = createGrant(ctx.channel, ctx.liveAsset, GRANT_FROM, GRANT_TO);
        return ctx;
    }

    /** 发布三段 10:00-10:30 / 10:30-11:00 / 11:00-11:30 的 movie 节目。 */
    private void publishProgram(Ctx ctx) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        String[][] defs = {
                {"seg1", T_1000, T_1030}, {"seg2", T_1030, T_1100}, {"seg3", T_1100, T_1130}};
        for (String[] def : defs) {
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("id", ctx.prefix + def[0]);
            segment.put("assetId", ctx.movieAsset);
            segment.put("start", def[1]);
            segment.put("end", def[2]);
            segments.add(segment);
        }
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", "draft-" + ctx.prefix);
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "pub-" + ctx.prefix,
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

    private ResultActions createLive(Ctx ctx, String requestId, String operator, String shortKey,
                                     String liveAsset, long grantId, String start, String end)
            throws Exception {
        return createLiveRaw(requestId, operator, ctx.key(shortKey), liveAsset, start, end,
                List.of(channelEntry(ctx.channel, grantId)));
    }

    private ResultActions createLiveRaw(String requestId, String operator, String overrideKey,
                                        String liveAsset, String start, String end,
                                        List<Map<String, Object>> channels) throws Exception {
        return postJson("/api/live-overrides",
                liveBody(requestId, operator, overrideKey, liveAsset, start, end, channels));
    }

    private static Map<String, Object> liveBody(String requestId, String operator,
                                                String overrideKey, String liveAsset,
                                                String start, String end,
                                                List<Map<String, Object>> channels) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("overrideKey", overrideKey);
        body.put("liveAssetId", liveAsset);
        body.put("start", start);
        body.put("end", end);
        body.put("channels", channels);
        return body;
    }

    private static Map<String, Object> channelEntry(String channelId, long grantId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("channelId", channelId);
        entry.put("grantId", grantId);
        return entry;
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

    private ResultActions revokeLive(Ctx ctx, String requestId, String operator, String shortKey)
            throws Exception {
        return postJson("/api/live-overrides/" + ctx.key(shortKey) + "/revoke",
                Map.of("requestId", requestId, "operator", operator));
    }

    private ResultActions settleLive(Ctx ctx, String requestId, String operator, String shortKey)
            throws Exception {
        return postJson("/api/live-overrides/" + ctx.key(shortKey) + "/settle",
                Map.of("requestId", requestId, "operator", operator));
    }

    private ResultActions getLive(Ctx ctx, String shortKey) throws Exception {
        return getLiveRaw(ctx.key(shortKey));
    }

    private ResultActions getLiveRaw(String overrideKey) throws Exception {
        return mvc.perform(get("/api/live-overrides/" + overrideKey));
    }

    private ResultActions getDiagnostics(Ctx ctx, String shortKey) throws Exception {
        return mvc.perform(get("/api/live-overrides/" + ctx.key(shortKey) + "/diagnostics"));
    }

    private ResultActions getHistory(Ctx ctx) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/live-overrides"));
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
