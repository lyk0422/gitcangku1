package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 直播覆盖令 API 端到端测试：主流程（创建/激活/结束恢复/错过）、失败分支与事务回滚、
 * 跨频道批量原子性、幂等快照、并发裁决、授权撤销与不可变证据。
 * 全部使用真实 H2（MODE=MySQL）内存库与可控固定时钟，不依赖外部服务。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveOverrideApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_0900 = "2026-09-22T09:00:00.000+08:00";
    private static final String T_1000 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_1010 = "2026-09-22T10:10:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1025 = "2026-09-22T10:25:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1035 = "2026-09-22T10:35:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_1050 = "2026-09-22T10:50:00.000+08:00";
    private static final String T_1100 = "2026-09-22T11:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlayoutClock playoutClock;

    /** 测试运行唯一前缀：共享内存库中 requestId 全局唯一，避免跨用例撞键（重放仍保持一致）。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @BeforeEach
    void fixClockBeforeDawn() {
        setClock(T_0900);
    }

    // ---------- 主流程：SCHEDULED → ACTIVE → ENDED，恢复与错过 ----------

    @Test
    void scheduledActivateEndResumeMissedAndPlayoutBranches() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        // 创建：09:00 创建 10:20-10:40 覆盖令 → SCHEDULED；抑制 s2(10:20-10:40)，s3 恰在结束时开播待恢复
        MvcResult created = createLive(ctx, "req-create", "op-a", "live-1",
                        ctx.channel, ctx.liveAsset, ctx.liveGrant, T_1020, T_1040)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.totalOverrideDurationMs").value(1_200_000))
                .andExpect(jsonPath("$.affectedCount").value(2))
                .andExpect(jsonPath("$.suppressedDurationMs").value(1_200_000))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[0].operator").value("op-a"))
                .andReturn();
        String key = ctx.key("live-1");
        long liveId = readJson(created).get("items").get(0).get("id").asLong();

        // 开始前诊断：未在播，未来覆盖不在截至窗口内
        diagnostics(ctx.channel, T_1010)
                .andExpect(jsonPath("$.liveOnAir").value(false))
                .andExpect(jsonPath("$.activeOverrideCount").value(0))
                .andExpect(jsonPath("$.liveOnAirMs").value(0));

        // 开始前查询 10:30：窗口命中直播源（读路径不依赖状态推进）
        playout(ctx, T_1010).andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.assetId").value(ctx.liveAsset))
                .andExpect(jsonPath("$.liveOverrideKey").value(key))
                .andExpect(jsonPath("$.overrideKey").isEmpty());

        // 到开始时刻推进 → ACTIVE
        setClock(T_1020);
        settle(ctx.channel);
        getLive(key)
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.startedAt").value(T_1020));
        diagnostics(ctx.channel, T_1030)
                .andExpect(jsonPath("$.liveOnAir").value(true))
                .andExpect(jsonPath("$.activeOverrideKey").value(key))
                .andExpect(jsonPath("$.sourceId").value(ctx.liveAsset))
                .andExpect(jsonPath("$.suppressedSegmentCount").value(1))
                .andExpect(jsonPath("$.liveOnAirMs").value(600_000));

        // 到结束时刻推进 → ENDED：s2 MISSED_BY_LIVE，s3 RESUMED
        setClock(T_1040);
        settle(ctx.channel);
        JsonNode detail = readJson(getLive(key).andReturn());
        assertThat(detail.get("status").asText()).isEqualTo("ENDED");
        assertThat(detail.get("endedAt").asText()).isEqualTo(T_1040);
        assertThat(detail.get("resumedCount").asInt()).isEqualTo(1);
        assertThat(detail.get("missedCount").asInt()).isEqualTo(1);
        JsonNode recoveries = detail.get("recoveryItems");
        assertThat(recoveries).hasSize(2);
        assertThat(recoveries.get(0).get("segmentId").asText()).isEqualTo(ctx.key("s2"));
        assertThat(recoveries.get(0).get("result").asText()).isEqualTo("MISSED_BY_LIVE");
        assertThat(recoveries.get(1).get("segmentId").asText()).isEqualTo(ctx.key("s3"));
        assertThat(recoveries.get(1).get("result").asText()).isEqualTo("RESUMED");
        assertThat(recoveries.get(1).get("recoveredAt").asText()).isEqualTo(T_1040);
        // 受影响素材结果同步为最终裁决，且带有裁决时间
        JsonNode affected = detail.get("affectedItems");
        assertThat(affected).hasSize(2);
        assertThat(affected.get(0).get("result").asText()).isEqualTo("MISSED_BY_LIVE");
        assertThat(affected.get(0).get("decidedAt").asText()).isEqualTo(T_1040);
        assertThat(affected.get(1).get("result").asText()).isEqualTo("RESUMED");

        // 结束后：错过素材不补播（即使查询覆盖内历史时刻），恢复素材正常播出
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("LIVE_PREEMPTED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset));
        playout(ctx, T_1050)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.segmentId").value(ctx.key("s3")));

        // 诊断：截至 11:00 实际直播 20 分钟，错过 1、恢复 1
        setClock(T_1100);
        diagnostics(ctx.channel, T_1100)
                .andExpect(jsonPath("$.liveOnAir").value(false))
                .andExpect(jsonPath("$.liveOnAirMs").value(1_200_000))
                .andExpect(jsonPath("$.missedSegmentCount").value(1))
                .andExpect(jsonPath("$.resumedSegmentCount").value(1));

        // 历史查询排序稳定且只读
        getJson("/api/live-overrides?channelId=" + ctx.channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(liveId))
                .andExpect(jsonPath("$[0].missedCount").value(1))
                .andExpect(jsonPath("$[0].resumedCount").value(1));
    }

    // ---------- 撤销：仅 SCHEDULED 可撤销，证据保留 ----------

    @Test
    void revokeOnlyBeforeStartAndEvidenceStays() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);
        createLive(ctx, "req-create", "op-a", "lv", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1040).andExpect(status().isOk());
        String key = ctx.key("lv");

        // 开始前撤销成功
        postJson("/api/live-overrides/" + key + "/revoke",
                        Map.of("requestId", rid("req-revoke"), "operator", "op-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value(rid("req-revoke")))
                .andExpect(jsonPath("$.revokeOperator").value("op-b"))
                .andExpect(jsonPath("$.revokedAt").value(T_0900))
                // 受影响素材与授权快照证据保留
                .andExpect(jsonPath("$.affectedItems.length()").value(2))
                .andExpect(jsonPath("$.grantSnapshot.revoked").value(false))
                .andExpect(jsonPath("$.grantSnapshot.grantId").value(ctx.liveGrant));

        // 撤销后窗口不再命中直播
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.segmentId").value(ctx.key("s2")));

        // 重复撤销 409
        postJson("/api/live-overrides/" + key + "/revoke",
                        Map.of("requestId", rid("req-revoke-2"), "operator", "op-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_ALREADY_REVOKED"));

        // ACTIVE 不可撤销
        createLive(ctx, "req-create-2", "op-a", "lv2", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                "2026-09-22T14:00:00.000+08:00", "2026-09-22T14:30:00.000+08:00")
                .andExpect(status().isOk());
        setClock("2026-09-22T14:00:00.000+08:00");
        settle(ctx.channel);
        postJson("/api/live-overrides/" + ctx.key("lv2") + "/revoke",
                        Map.of("requestId", rid("req-revoke-3"), "operator", "op-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_NOT_REVOKABLE"));

        // 404：不存在
        postJson("/api/live-overrides/" + ctx.prefix + "ghost/revoke",
                        Map.of("requestId", rid("req-revoke-x"), "operator", "op-b"))
                .andExpect(status().isNotFound());
    }

    // ---------- 失败分支：重叠/授权/已开始节目/过去区间/保底源 ----------

    @Test
    void validationFailuresAreDistinguishable() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        // 422：结束不大于开始（400 由 Bean Validation 之外的业务校验给出）
        createLiveRaw("bad-interval", "op-a", ctx.key("bad1"), ctx.channel, ctx.liveAsset,
                ctx.liveGrant, T_1040, T_1020)
                .andExpect(status().isBadRequest());

        // 422：授权未完整覆盖（授权 10:00-10:20，覆盖 10:10-10:30），消息给出实际值与差额
        long shortGrant = createGrant(ctx.channel, ctx.liveAsset, T_1000, T_1020);
        MvcResult notCovering = createLiveRaw("nc", "op-a", ctx.key("nc"), ctx.channel,
                        ctx.liveAsset, shortGrant, T_1010, T_1030)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"))
                .andReturn();
        assertThat(readJson(notCovering).get("message").asText())
                .contains("差额")
                .contains("600000");

        // 422：授权属于其他频道
        Ctx other = newContext();
        createLiveRaw("nm", "op-a", ctx.key("nm"), ctx.channel, ctx.liveAsset,
                        other.liveGrant, T_1025, T_1035)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));

        // 422：授权已撤销
        long revoked = createGrant(ctx.channel, ctx.liveAsset, GRANT_FROM, GRANT_TO);
        postJson("/api/grants/" + revoked + "/revoke", Map.of("requestId", rid("rv-live-1")))
                .andExpect(status().isOk());
        createLiveRaw("rv", "op-a", ctx.key("rv"), ctx.channel, ctx.liveAsset,
                        revoked, "2026-09-22T13:00:00.000+08:00", "2026-09-22T13:10:00.000+08:00")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 422：保底素材不能作为直播源
        createLiveRaw("fb", "op-a", ctx.key("fb"), ctx.channel, ctx.fallbackAsset,
                        ctx.movieGrant, T_1025, T_1035)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));

        // 422：区间完全在过去
        setClock(T_1100);
        createLiveRaw("past", "op-a", ctx.key("past"), ctx.channel, ctx.liveAsset,
                        ctx.liveGrant, T_1000, T_1020)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LIVE_INTERVAL_IN_PAST"));

        // 409：与已开始节目冲突（10:30 时 s2 正在播出，覆盖 10:25 开始会截断在播节目）
        setClock(T_1030);
        createLiveRaw("started", "op-a", ctx.key("started"), ctx.channel, ctx.liveAsset,
                        ctx.liveGrant, T_1025, "2026-09-22T10:45:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_PROGRAM_ALREADY_STARTED"));

        // 失败不留记录、不占键
        getLive(ctx.key("nc")).andExpect(status().isNotFound());
        getLive(ctx.key("started")).andExpect(status().isNotFound());
    }

    @Test
    void overlappingLiveWindowsConflictButAdjacentAllowed() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        createLive(ctx, "r1", "op-a", "a", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1030).andExpect(status().isOk());
        // 409：同频道窗口重叠
        createLive(ctx, "r2", "op-a", "b", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1025, T_1035)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));
        // 相邻合法（左闭右开）
        createLive(ctx, "r3", "op-a", "c", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1030, T_1040).andExpect(status().isOk());
        // 409：重复键
        createLive(ctx, "r4", "op-a", "a", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                "2026-09-22T15:00:00.000+08:00", "2026-09-22T15:10:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_LIVE_OVERRIDE_KEY"));
    }

    // ---------- 与紧急插播互斥，按提交顺序后者拒绝 ----------

    @Test
    void liveAndEmergencyAreMutuallyExclusiveByCommitOrder() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        // 紧急插播先提交 → 直播覆盖 409
        postJson("/api/emergency-overrides", overrideBody("em-1", ctx.key("em1"), ctx.channel,
                        ctx.newsAsset, ctx.newsGrant, 5, T_1020, T_1030))
                .andExpect(status().isOk());
        createLive(ctx, "lv-1", "op-a", "lv1", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1025, T_1035)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMERGENCY_OVERRIDE_CONFLICT"));

        // 另一时段直播先提交 → 紧急插播 409
        createLive(ctx, "lv-2", "op-a", "lv2", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                "2026-09-22T13:00:00.000+08:00", "2026-09-22T13:20:00.000+08:00")
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", overrideBody("em-2", ctx.key("em2"), ctx.channel,
                        ctx.newsAsset, ctx.newsGrant, 5,
                        "2026-09-22T13:05:00.000+08:00", "2026-09-22T13:15:00.000+08:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));
    }

    // ---------- 跨频道批量：任一失败全量回滚、不留半成品 ----------

    @Test
    void crossChannelBatchAllOrNothingAndFailureFreesRequestId() throws Exception {
        Ctx a = newContext();
        publishThreeSegments(a);
        Ctx b = newContext();
        publishThreeSegments(b);

        List<Map<String, Object>> items = List.of(
                liveItem(a.key("ok"), a.channel, a.liveAsset, a.liveGrant, T_1020, T_1040),
                liveItem(b.key("bad"), b.channel, b.liveAsset, a.liveGrant, T_1020, T_1040));
        // 第二条授权不属于频道 b：整批 422
        postJson("/api/live-overrides",
                        batchBody("batch-req", "op-a", items))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));
        // 不留部分覆盖
        getLive(a.key("ok")).andExpect(status().isNotFound());
        getLive(b.key("bad")).andExpect(status().isNotFound());

        // 失败不占 requestId：同一 requestId 修正后成功，且一次提交两条
        List<Map<String, Object>> fixed = List.of(
                liveItem(a.key("ok"), a.channel, a.liveAsset, a.liveGrant, T_1020, T_1040),
                liveItem(b.key("good"), b.channel, b.liveAsset, b.liveGrant, T_1020, T_1040));
        postJson("/api/live-overrides", batchBody("batch-req", "op-a", fixed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.channelIds.length()").value(2));
        getLive(a.key("ok")).andExpect(jsonPath("$.status").value("SCHEDULED"));
        getLive(b.key("good")).andExpect(jsonPath("$.status").value("SCHEDULED"));

        // 批次内自相重叠也整体失败
        List<Map<String, Object>> selfOverlap = List.of(
                liveItem(a.key("x1"), a.channel, a.liveAsset, a.liveGrant,
                        "2026-09-22T15:00:00.000+08:00", "2026-09-22T15:20:00.000+08:00"),
                liveItem(a.key("x2"), a.channel, a.liveAsset, a.liveGrant,
                        "2026-09-22T15:10:00.000+08:00", "2026-09-22T15:30:00.000+08:00"));
        postJson("/api/live-overrides", batchBody("batch-req-2", "op-a", selfOverlap))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LIVE_OVERRIDE_INTERVAL_CONFLICT"));
        getLive(a.key("x1")).andExpect(status().isNotFound());
    }

    // ---------- 幂等：同参快照重放、异参/异操作者 409、失败不占键 ----------

    @Test
    void idempotencySnapshotReplayAndChangedParamsConflict() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        Map<String, Object> body = batchBody("idem-req", "op-a", List.of(
                liveItem(ctx.key("io"), ctx.channel, ctx.liveAsset, ctx.liveGrant, T_1020, T_1040)));
        String first = postJson("/api/live-overrides", body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 推进状态后重放：仍返回首次完整响应快照（SCHEDULED）
        setClock(T_1040);
        settle(ctx.channel);
        getLive(ctx.key("io")).andExpect(jsonPath("$.status").value("ENDED"));
        String replay = postJson("/api/live-overrides", body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay)).isEqualTo(objectMapper.readTree(first));

        // 异参（时间）409
        postJson("/api/live-overrides", batchBody("idem-req", "op-a", List.of(
                        liveItem(ctx.key("io"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                                T_1020, T_1030))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 异操作者 409（操作者进入指纹）
        postJson("/api/live-overrides", batchBody("idem-req", "op-other", List.of(
                        liveItem(ctx.key("io"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                                T_1020, T_1040))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 异操作（撤销复用创建 requestId）409
        postJson("/api/live-overrides/" + ctx.key("io") + "/revoke",
                        Map.of("requestId", rid("idem-req"), "operator", "op-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 集合顺序变化但内容相同：规范化后指纹一致，视为重放
        Map<String, Object> reordered = batchBody("idem-req-2", "op-a", List.of(
                liveItem(ctx.key("zz"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T16:00:00.000+08:00", "2026-09-22T16:10:00.000+08:00"),
                liveItem(ctx.key("aa"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T17:00:00.000+08:00", "2026-09-22T17:10:00.000+08:00")));
        postJson("/api/live-overrides", reordered).andExpect(status().isOk());
        Map<String, Object> reversed = batchBody("idem-req-2", "op-a", List.of(
                liveItem(ctx.key("aa"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T17:00:00.000+08:00", "2026-09-22T17:10:00.000+08:00"),
                liveItem(ctx.key("zz"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T16:00:00.000+08:00", "2026-09-22T16:10:00.000+08:00")));
        postJson("/api/live-overrides", reversed).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(2));
    }

    // ---------- 并发：同频道时段仅一个有效覆盖 ----------

    @Test
    void concurrentOverlappingLiveOverridesOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);
        String url = "/api/live-overrides";

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchBody(
                                "cc-req-" + ctx.prefix + i, "op-a", List.of(liveItem(
                                        ctx.key("cc" + i), ctx.channel, ctx.liveAsset,
                                        ctx.liveGrant, T_1020, T_1040))))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        int active = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult r = getLive(ctx.key("cc" + i)).andReturn();
            if (r.getResponse().getStatus() == 200) {
                active++;
            }
        }
        assertThat(active).isEqualTo(1);
    }

    // ---------- 直播进行中授权撤销：窗口内不放行原素材，结束仍记错过 ----------

    @Test
    void grantRevokedDuringLiveBlocksBothLiveAndProgram() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);
        createLive(ctx, "req-lv", "op-a", "lv", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1040).andExpect(status().isOk());
        String key = ctx.key("lv");

        setClock(T_1020);
        settle(ctx.channel);
        playout(ctx, T_1030).andExpect(jsonPath("$.source").value("LIVE"));

        // 直播授权撤销：直播不再播出，原素材保留占位也不播出 → 保底 LIVE_PREEMPTED
        postJson("/api/grants/" + ctx.liveGrant + "/revoke", Map.of("requestId", rid("rv-live-2")))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("LIVE_PREEMPTED"))
                .andExpect(jsonPath("$.liveOverrideKey").value(key));

        // 授权快照证据不被改写
        getLive(key).andExpect(jsonPath("$.grantSnapshot.revoked").value(false));

        // 结束裁决：s2 错过不补播
        setClock(T_1040);
        settle(ctx.channel);
        getLive(key)
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.missedCount").value(1))
                .andExpect(jsonPath("$.resumedCount").value(1));
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("LIVE_PREEMPTED"));
    }

    // ---------- 覆盖创建后再发版：受影响证据锁定旧快照，不改写历史 ----------

    @Test
    void laterRepublishDoesNotRewriteAffectedEvidence() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);
        createLive(ctx, "req-lv", "op-a", "lv", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1040).andExpect(status().isOk());
        String key = ctx.key("lv");
        long evidencePublicationId = readJson(getLive(key).andReturn())
                .get("affectedItems").get(0).get("publicationId").asLong();

        // 覆盖创建后修改草稿并发布新版本
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, replaceBody(rid("draft-v2"), 1,
                        List.of(segment(ctx.key("n1"), ctx.movieAsset, T_1000, T_1100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", rid("pub-v2"), "draftVersion", 2,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isOk());

        // 旧证据仍指向版本 1 的快照
        JsonNode detail = readJson(getLive(key).andReturn());
        assertThat(detail.get("affectedItems").get(0).get("publicationId").asLong())
                .isEqualTo(evidencePublicationId);
        assertThat(detail.get("affectedItems").get(0).get("segmentId").asText())
                .isEqualTo(ctx.key("s2"));
    }

    // ---------- 并发：直播覆盖创建与授权撤销按提交顺序裁决 ----------

    @Test
    void concurrentCreateAndGrantRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);
        String url = "/api/live-overrides";
        Map<String, Object> createBody = batchBody("race-create", "op-a", List.of(
                liveItem(ctx.key("race"), ctx.channel, ctx.liveAsset, ctx.liveGrant,
                        "2026-09-22T13:00:00.000+08:00", "2026-09-22T13:20:00.000+08:00")));

        List<Integer> statuses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(createBody)))
                        .andReturn().getResponse().getStatus();
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/grants/" + ctx.liveGrant + "/revoke",
                                Map.of("requestId", rid("race-revoke")))
                        .andReturn().getResponse().getStatus();
            }));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // 撤销必成功；创建要么 200（先提交），要么 422（撤销先提交，授权失效）
        assertThat(statuses).contains(200);
        int createStatus = statuses.get(0);
        assertThat(createStatus).isIn(200, 422);
        MvcResult detail = getLive(ctx.key("race")).andReturn();
        if (createStatus == 422) {
            assertThat(detail.getResponse().getStatus()).isEqualTo(404);
        } else {
            assertThat(detail.getResponse().getStatus()).isEqualTo(200);
            assertThat(readJson(detail).get("grantSnapshot").get("revoked").asBoolean()).isFalse();
        }
    }

    // ---------- 撤销后时段释放；历史窗口过滤与排序 ----------

    @Test
    void revokeReleasesWindowAndHistoryWindowFiltering() throws Exception {
        Ctx ctx = newContext();
        publishThreeSegments(ctx);

        // 撤销 10:20-10:40 的覆盖
        createLive(ctx, "rel-1", "op-a", "old", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1040).andExpect(status().isOk());
        postJson("/api/live-overrides/" + ctx.key("old") + "/revoke",
                        Map.of("requestId", rid("rel-rv"), "operator", "op-b"))
                .andExpect(status().isOk());
        // 同一时段可重新创建覆盖（撤销释放互斥）
        createLive(ctx, "rel-2", "op-a", "new", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                T_1020, T_1040)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED"));

        // 另一时段再建一条，用于历史窗口与排序
        createLive(ctx, "rel-3", "op-a", "later", ctx.channel, ctx.liveAsset, ctx.liveGrant,
                "2026-09-22T15:00:00.000+08:00", "2026-09-22T15:30:00.000+08:00")
                .andExpect(status().isOk());

        // 历史窗口 [10:30, 10:35)：与旧/新 10:20-10:40 相交（含 REVOKED 旧令与 SCHEDULED 新令），不含 15:00
        MvcResult morning = mvc.perform(get("/api/live-overrides")
                        .param("channelId", ctx.channel)
                        .param("from", "2026-09-22T10:30:00.000+08:00")
                        .param("to", "2026-09-22T10:35:00.000+08:00"))
                .andExpect(status().isOk()).andReturn();
        JsonNode morningJson = readJson(morning);
        assertThat(morningJson).hasSize(2);
        // 排序稳定：开始时间升序、ID 升序
        assertThat(morningJson.get(0).get("overrideKey").asText()).isEqualTo(ctx.key("old"));
        assertThat(morningJson.get(1).get("overrideKey").asText()).isEqualTo(ctx.key("new"));

        // 仅 15:00 之后窗口：只有 later
        MvcResult afternoon = mvc.perform(get("/api/live-overrides")
                        .param("channelId", ctx.channel)
                        .param("from", "2026-09-22T14:00:00.000+08:00")
                        .param("to", "2026-09-22T16:00:00.000+08:00"))
                .andExpect(status().isOk()).andReturn();
        JsonNode afternoonJson = readJson(afternoon);
        assertThat(afternoonJson).hasSize(1);
        assertThat(afternoonJson.get(0).get("overrideKey").asText()).isEqualTo(ctx.key("later"));

        // 404：明细不存在
        getLive(ctx.prefix + "missing").andExpect(status().isNotFound());
    }

    // ---------- 测试辅助 ----------

    private final class Ctx {
        private final String prefix = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String liveAsset;
        private String newsAsset;
        private long movieGrant;
        private long liveGrant;
        private long newsGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.liveAsset = ctx.prefix + "live";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.liveAsset, "durationMs", 7200000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset);
        ctx.liveGrant = createGrant(ctx.channel, ctx.liveAsset);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset);
        return ctx;
    }

    /** 发布 s1 10:00-10:20、s2 10:20-10:40、s3 10:40-11:00 的 movie 编排（发布版本 1）。 */
    private void publishThreeSegments(Ctx ctx) throws Exception {
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, replaceBody(rid("draft-" + ctx.prefix), 0,
                        List.of(
                                segment(ctx.key("s1"), ctx.movieAsset, T_1000, T_1020),
                                segment(ctx.key("s2"), ctx.movieAsset, T_1020, T_1040),
                                segment(ctx.key("s3"), ctx.movieAsset, T_1040, T_1100))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", rid("pub-" + ctx.prefix), "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
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
                                     String channelId, String sourceId, long grantId,
                                     String start, String end) throws Exception {
        return createLiveRaw(requestId, operator, ctx.key(shortKey), channelId, sourceId,
                grantId, start, end);
    }

    private ResultActions createLiveRaw(String requestId, String operator, String overrideKey,
                                        String channelId, String sourceId, long grantId,
                                        String start, String end) throws Exception {
        return postJson("/api/live-overrides",
                batchBody(requestId, operator,
                        List.of(liveItem(overrideKey, channelId, sourceId, grantId, start, end))));
    }

    private static Map<String, Object> liveItem(String overrideKey, String channelId,
                                                String sourceId, long grantId,
                                                String start, String end) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("overrideKey", overrideKey);
        item.put("channelId", channelId);
        item.put("sourceId", sourceId);
        item.put("grantId", grantId);
        item.put("start", start);
        item.put("end", end);
        return item;
    }

    private Map<String, Object> batchBody(String requestId, String operator,
                                          List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", rid(requestId));
        body.put("operator", operator);
        body.put("items", items);
        return body;
    }

    private String rid(String requestId) {
        return "run" + runId + "-" + requestId;
    }

    private Map<String, Object> overrideBody(String requestId, String overrideKey,
                                             String channelId, String assetId, long grantId,
                                             int priority, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", rid(requestId));
        body.put("overrideKey", overrideKey);
        body.put("channelId", channelId);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> segment(String id, String assetId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> replaceBody(String requestId, long expectedVersion,
                                                   List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return body;
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout").param("at", at));
    }

    private ResultActions diagnostics(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/live-diagnostics").param("at", at));
    }

    private ResultActions settle(String channel) throws Exception {
        return postJson("/api/channels/" + channel + "/live-overrides/settle", new LinkedHashMap<>());
    }

    private ResultActions getLive(String key) throws Exception {
        return getJson("/api/live-overrides/" + key);
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void setClock(String offsetTime) {
        Instant instant = OffsetDateTime.parse(offsetTime).toInstant();
        playoutClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
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
