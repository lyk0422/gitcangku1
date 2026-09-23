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
 * 播出决定固化与回执 API 端到端测试：主流程（PROGRAM/EMERGENCY/FALLBACK 固化、回执生命周期、
 * 明细与列表查询）、失败分支（400/404/409）、幂等（不重算不覆盖）、提交顺序裁决与并发边界。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutDecisionApiTest {

    private static final String DAY = "2026-09-23";
    private static final String GRANT_FROM = "2026-09-23T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-24T00:00:00.000+08:00";
    private static final String T_1000 = "2026-09-23T10:00:00.000+08:00";
    private static final String T_1020 = "2026-09-23T10:20:00.000+08:00";
    private static final String T_1030 = "2026-09-23T10:30:00.000+08:00";
    private static final String T_1040 = "2026-09-23T10:40:00.000+08:00";
    private static final String T_1050 = "2026-09-23T10:50:00.000+08:00";
    private static final String T_1100 = "2026-09-23T11:00:00.000+08:00";
    private static final String T_1200 = "2026-09-23T12:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：PROGRAM 固化 + 回执生命周期 + 明细/列表查询 ----------

    @Test
    void registerProgramDecisionAndReceiptLifecycle() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);

        // 登记：命中已发布节目片段，固化素材、来源、快照与授权
        register(ctx, "req-reg-1", "pk-1", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playKey").value(ctx.key("pk-1")))
                .andExpect(jsonPath("$.channelId").value(ctx.channel))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.publicationId").isNotEmpty())
                .andExpect(jsonPath("$.segmentId").value(ctx.key("seg")))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant))
                .andExpect(jsonPath("$.overrideKey").isEmpty())
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.receiptStatus").value("PENDING"))
                .andExpect(jsonPath("$.receiptNote").isEmpty())
                .andExpect(jsonPath("$.receiptReportedAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // 明细：无回执显示 PENDING
        getDecision(ctx, "pk-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.receiptStatus").value("PENDING"));

        // 首次回执：固化结果与时刻
        submitReceipt(ctx, "pk-1", "req-rc-1", "PLAYED", "正常播出")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.receiptNote").value("正常播出"))
                .andExpect(jsonPath("$.receiptReportedAt").isNotEmpty())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));

        // 明细显示已固化回执
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.receiptNote").value("正常播出"));

        // 回执不改变节目与授权：实时查询仍是 PROGRAM
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));

        // 按频道+业务日查询
        listDecisions(ctx, DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].playKey").value(ctx.key("pk-1")))
                .andExpect(jsonPath("$[0].receiptStatus").value("PLAYED"));

        // 原实时查询保持只读可用
        playout(ctx, T_1030).andExpect(status().isOk());
    }

    // ---------- 主流程：EMERGENCY / FALLBACK 各来源固化 ----------

    @Test
    void registerEmergencyAndFallbackDecisions() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);
        createOverride(ctx, "req-ov", "ov-1", ctx.newsAsset, ctx.newsGrant, 5, T_1020, T_1040)
                .andExpect(status().isOk());

        // EMERGENCY：固化 overrideKey 与插播授权
        register(ctx, "req-reg-em", "pk-em", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant))
                .andExpect(jsonPath("$.publicationId").isEmpty())
                .andExpect(jsonPath("$.segmentId").isEmpty());

        // PROGRAM：插播区间外
        register(ctx, "req-reg-pg", "pk-pg", T_1050)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));

        // FALLBACK GAP：有发布但时刻处于空档，保底无授权 grantId 为 null
        register(ctx, "req-reg-gap", "pk-gap", T_1200)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.grantId").isEmpty())
                .andExpect(jsonPath("$.publicationId").isEmpty());

        // FALLBACK NO_PUBLISHED_SCHEDULE：无发布频道
        Ctx bare = newContext();
        register(bare, "req-reg-np", "pk-np", T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(bare.fallbackAsset))
                .andExpect(jsonPath("$.grantId").isEmpty());

        // FALLBACK GRANT_REVOKED：撤销先提交，登记固化为失效后来源，保留快照引用但授权为 null
        postJson("/api/grants/" + ctx.movieGrant + "/revoke",
                        Map.of("requestId", ctx.prefix + "req-rv-movie"))
                .andExpect(status().isOk());
        register(ctx, "req-reg-rv", "pk-rv", T_1050)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.grantId").isEmpty())
                .andExpect(jsonPath("$.publicationId").isNotEmpty())
                .andExpect(jsonPath("$.segmentId").value(ctx.key("seg")));
    }

    // ---------- 失败分支：400 / 404 与失败回滚不占键 ----------

    @Test
    void validationNotFoundAndFailureRollback() throws Exception {
        Ctx ctx = newContext();

        // 400：缺少必填字段（Bean Validation）
        postJson("/api/playout-decisions", Map.of(
                        "playKey", ctx.key("b1"), "channelId", ctx.channel, "at", T_1030))
                .andExpect(status().isBadRequest());
        postJson("/api/playout-decisions", Map.of(
                        "requestId", "b2", "channelId", ctx.channel, "at", T_1030))
                .andExpect(status().isBadRequest());
        postJson("/api/playout-decisions", Map.of(
                        "requestId", "b3", "playKey", ctx.key("b3"), "channelId", ctx.channel))
                .andExpect(status().isBadRequest());

        // 404：频道不存在
        registerRaw(ctx.prefix + "req-nf-ch", ctx.key("pk-nf"), ctx.prefix + "ghost", T_1030)
                .andExpect(status().isNotFound());
        // 404：明细不存在
        getDecisionRaw(ctx.prefix + "ghost").andExpect(status().isNotFound());
        // 404：回执目标决定不存在
        submitReceiptRaw(ctx.prefix + "ghost", ctx.prefix + "req-nf-rc", "PLAYED", "说明")
                .andExpect(status().isNotFound());
        // 404：列表频道不存在
        mvc.perform(get("/api/channels/" + ctx.prefix + "ghost/playout-decisions")
                        .param("businessDay", DAY))
                .andExpect(status().isNotFound());
        // 400：业务日格式非法
        mvc.perform(get("/api/channels/" + ctx.channel + "/playout-decisions")
                        .param("businessDay", "2026/09/23"))
                .andExpect(status().isBadRequest());

        // 回执参数 400：result 非法、note 空、缺 requestId
        register(ctx, "req-reg-ok", "pk-ok", T_1030).andExpect(status().isOk());
        submitReceipt(ctx, "pk-ok", "req-bad-1", "DONE", "说明")
                .andExpect(status().isBadRequest());
        submitReceipt(ctx, "pk-ok", "req-bad-2", "PLAYED", "  ")
                .andExpect(status().isBadRequest());
        postJson("/api/playout-decisions/" + ctx.key("pk-ok") + "/receipts",
                        Map.of("result", "PLAYED", "note", "说明"))
                .andExpect(status().isBadRequest());

        // 失败回滚：404/400 不产生决定与回执，也不占 requestId
        getDecision(ctx, "pk-nf").andExpect(status().isNotFound());
        registerRaw(ctx.prefix + "req-nf-ch", ctx.key("pk-nf"), ctx.channel, T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"));
        getDecision(ctx, "pk-ok")
                .andExpect(jsonPath("$.receiptStatus").value("PENDING"));
        submitReceipt(ctx, "pk-ok", "req-bad-1", "PLAYED", "重试成功")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"));
    }

    // ---------- 幂等：同键同参重放、改参 409、不重新计算不覆盖 ----------

    @Test
    void registerIdempotencyAndNoRecompute() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);

        Map<String, Object> body = registerBody(ctx.prefix + "req-idem", ctx.key("pk-i"), ctx.channel, T_1030);
        MvcResult first = postJson("/api/playout-decisions", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andReturn();
        String firstCreatedAt = readJson(first).get("createdAt").asText();

        // 同 requestId 同参数重放：返回首次响应
        postJson("/api/playout-decisions", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(firstCreatedAt));
        // 同 requestId 改时刻：409
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem", ctx.key("pk-i"), ctx.channel, T_1050))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改 playKey：409
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem", ctx.key("pk-i2"), ctx.channel, T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 不同 requestId、相同 playKey 且相同频道、时刻：返回原决定
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem-2", ctx.key("pk-i"), ctx.channel, T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(firstCreatedAt));
        // 不同 requestId、相同 playKey 改时刻：409
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem-3", ctx.key("pk-i"), ctx.channel, T_1050))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAY_KEY_CONFLICT"));
        // 相同 playKey 改频道：409
        Ctx other = newContext();
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem-4", ctx.key("pk-i"), other.channel, T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAY_KEY_CONFLICT"));

        // 不得重新计算或覆盖：撤销授权后重登同 playKey 仍返回原 PROGRAM 决定
        postJson("/api/grants/" + ctx.movieGrant + "/revoke",
                        Map.of("requestId", ctx.prefix + "req-rv"))
                .andExpect(status().isOk());
        playout(ctx, T_1030).andExpect(jsonPath("$.source").value("FALLBACK"));
        postJson("/api/playout-decisions",
                        registerBody(ctx.prefix + "req-idem-5", ctx.key("pk-i"), ctx.channel, T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));
        getDecision(ctx, "pk-i")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));
    }

    // ---------- 回执幂等：同内容返回已有回执，改结果或说明 409 ----------

    @Test
    void receiptIdempotencyAndConflict() throws Exception {
        Ctx ctx = newContext();
        register(ctx, "req-reg", "pk-r", T_1030).andExpect(status().isOk());

        MvcResult first = submitReceipt(ctx, "pk-r", "req-rc", "FAILED", "设备故障")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("FAILED"))
                .andReturn();
        String reportedAt = readJson(first).get("receiptReportedAt").asText();

        // 同 requestId 同参重放
        submitReceipt(ctx, "pk-r", "req-rc", "FAILED", "设备故障")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptReportedAt").value(reportedAt));
        // 同 requestId 改说明：409
        submitReceipt(ctx, "pk-r", "req-rc", "FAILED", "改说明")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 不同 requestId 提交相同结果和说明：返回已有回执，时刻不变
        submitReceipt(ctx, "pk-r", "req-rc-2", "FAILED", "设备故障")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("FAILED"))
                .andExpect(jsonPath("$.receiptReportedAt").value(reportedAt));
        // 不同 requestId 改结果：409
        submitReceipt(ctx, "pk-r", "req-rc-3", "PLAYED", "设备故障")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECEIPT_CONFLICT"));
        // 不同 requestId 改说明：409
        submitReceipt(ctx, "pk-r", "req-rc-4", "FAILED", "其他说明")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECEIPT_CONFLICT"));

        // 回执仍只有首次那一份
        getDecision(ctx, "pk-r")
                .andExpect(jsonPath("$.receiptStatus").value("FAILED"))
                .andExpect(jsonPath("$.receiptNote").value("设备故障"))
                .andExpect(jsonPath("$.receiptReportedAt").value(reportedAt));
    }

    // ---------- 提交顺序裁决：撤销/取消与固化的先后 ----------

    @Test
    void decisionFollowsCommitOrderAndReceiptAfterRevoke() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);

        // 固化先提交：登记为 PROGRAM 后撤销授权，固化记录保留当时结果，实时查询转为 FALLBACK
        register(ctx, "req-reg-a", "pk-a", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));
        postJson("/api/grants/" + ctx.movieGrant + "/revoke",
                        Map.of("requestId", ctx.prefix + "req-rv-a"))
                .andExpect(status().isOk());
        getDecision(ctx, "pk-a")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));

        // 授权后来撤销不阻止对已固化记录提交回执
        submitReceipt(ctx, "pk-a", "req-rc-a", "PLAYED", "撤销后补录回执")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.source").value("PROGRAM"));

        // 取消先提交：插播取消后登记不再命中 EMERGENCY
        createOverride(ctx, "req-ov-b", "ov-b", ctx.newsAsset, ctx.newsGrant, 5, T_1020, T_1040)
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides/" + ctx.key("ov-b") + "/cancel",
                        Map.of("requestId", ctx.prefix + "req-cancel-b"))
                .andExpect(status().isOk());
        register(ctx, "req-reg-b", "pk-b", T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));

        // 固化 EMERGENCY 后取消插播：固化记录保留 EMERGENCY，实时查询回到节目逻辑
        Ctx ctx2 = newContext();
        publishProgram(ctx2, "seg", T_1000, T_1100);
        createOverride(ctx2, "req-ov-c", "ov-c", ctx2.newsAsset, ctx2.newsGrant, 5, T_1020, T_1040)
                .andExpect(status().isOk());
        register(ctx2, "req-reg-c", "pk-c", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.overrideKey").value(ctx2.key("ov-c")));
        postJson("/api/emergency-overrides/" + ctx2.key("ov-c") + "/cancel",
                        Map.of("requestId", ctx2.prefix + "req-cancel-c"))
                .andExpect(status().isOk());
        getDecision(ctx2, "pk-c")
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.overrideKey").value(ctx2.key("ov-c")))
                .andExpect(jsonPath("$.grantId").value(ctx2.newsGrant));
        playout(ctx2, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"));
    }

    // ---------- 列表排序：按播出时刻、playKey ----------

    @Test
    void listDecisionsSortedByAtAndPlayKey() throws Exception {
        Ctx ctx = newContext();
        register(ctx, "r1", "pk-c", T_1030).andExpect(status().isOk());
        register(ctx, "r2", "pk-b", T_1000).andExpect(status().isOk());
        register(ctx, "r3", "pk-a", T_1000).andExpect(status().isOk());

        MvcResult result = listDecisions(ctx, DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();
        JsonNode list = readJson(result);
        assertThat(list.get(0).get("playKey").asText()).isEqualTo(ctx.key("pk-a"));
        assertThat(list.get(1).get("playKey").asText()).isEqualTo(ctx.key("pk-b"));
        assertThat(list.get(2).get("playKey").asText()).isEqualTo(ctx.key("pk-c"));

        // 其他业务日为空
        listDecisions(ctx, "2026-09-24")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 并发：同 playKey 登记只产生一份决定 ----------

    @Test
    void concurrentRegisterSamePlayKeySingleDecision() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);

        List<MvcResult> results = runConcurrently(4, i -> postJson("/api/playout-decisions",
                        registerBody("cc-reg-" + ctx.prefix + i, ctx.key("pk-cc"), ctx.channel, T_1030))
                .andReturn());
        // 全部成功且返回同一份决定
        assertThat(results).allMatch(r -> r.getResponse().getStatus() == 200);
        String createdAt = readJson(results.get(0)).get("createdAt").asText();
        for (MvcResult result : results) {
            JsonNode json = readJson(result);
            assertThat(json.get("source").asText()).isEqualTo("PROGRAM");
            assertThat(json.get("grantId").asLong()).isEqualTo(ctx.movieGrant);
            assertThat(json.get("createdAt").asText()).isEqualTo(createdAt);
        }
        // 库内只有一份决定
        listDecisions(ctx, DAY)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].playKey").value(ctx.key("pk-cc")));
    }

    // ---------- 并发：同 playKey 回执只产生一份；不同内容恰一成功 ----------

    @Test
    void concurrentReceiptSingleReceipt() throws Exception {
        Ctx ctx = newContext();
        register(ctx, "req-reg", "pk-cr", T_1030).andExpect(status().isOk());

        // 相同内容并发回执：全部成功，回执只有一份
        List<MvcResult> same = runConcurrently(4, i -> submitReceipt(ctx, "pk-cr",
                        "cc-rc-" + ctx.prefix + i, "PLAYED", "并发相同回执")
                .andReturn());
        assertThat(same).allMatch(r -> r.getResponse().getStatus() == 200);
        String reportedAt = readJson(same.get(0)).get("receiptReportedAt").asText();
        for (MvcResult result : same) {
            assertThat(readJson(result).get("receiptReportedAt").asText()).isEqualTo(reportedAt);
        }
        getDecision(ctx, "pk-cr")
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.receiptNote").value("并发相同回执"));

        // 不同内容并发回执：恰好一个成功，最终回执为成功那一份
        register(ctx, "req-reg-2", "pk-cr2", T_1030).andExpect(status().isOk());
        List<Integer> statuses = runConcurrently(2, i -> {
            String result = i == 0 ? "PLAYED" : "FAILED";
            return submitReceipt(ctx, "pk-cr2", "cc-rc2-" + ctx.prefix + i, result, "结果" + i)
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        MvcResult detail = getDecision(ctx, "pk-cr2").andReturn();
        String finalStatus = readJson(detail).get("receiptStatus").asText();
        assertThat(finalStatus).isIn("PLAYED", "FAILED");
    }

    // ---------- 并发：登记与授权撤销按提交顺序裁决 ----------

    @Test
    void concurrentRegisterAndRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg", T_1000, T_1100);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> registerFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/playout-decisions",
                                registerBody(ctx.prefix + "race-reg", ctx.key("pk-race"), ctx.channel, T_1030))
                        .andReturn();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/grants/" + ctx.movieGrant + "/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("requestId", ctx.prefix + "race-rv"))))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            MvcResult registerResult = registerFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            // 撤销本身成功；登记必成功，结果由提交顺序决定
            assertThat(revokeStatus).isEqualTo(200);
            assertThat(registerResult.getResponse().getStatus()).isEqualTo(200);
            JsonNode decision = readJson(registerResult);
            String source = decision.get("source").asText();
            if (source.equals("PROGRAM")) {
                // 固化先提交：保留当时有效结果与授权
                assertThat(decision.get("grantId").asLong()).isEqualTo(ctx.movieGrant);
            } else {
                // 撤销先提交：固化为失效后来源，无授权
                assertThat(source).isEqualTo("FALLBACK");
                assertThat(decision.get("reason").asText()).isEqualTo("GRANT_REVOKED");
                assertThat(decision.get("grantId").isNull()).isTrue();
            }
            // 明细与登记响应一致；实时查询在撤销提交后为 FALLBACK
            getDecision(ctx, "pk-race")
                    .andExpect(jsonPath("$.source").value(source));
            playout(ctx, T_1030)
                    .andExpect(jsonPath("$.source").value("FALLBACK"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、普通素材 movie/news、频道及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
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
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        return ctx;
    }

    /** 发布一段节目，作为登记下层的原节目。 */
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
        body.put("requestId", ctx.prefix + requestId);
        body.put("overrideKey", ctx.key(shortKey));
        body.put("channelId", ctx.channel);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return postJson("/api/emergency-overrides", body);
    }

    private ResultActions register(Ctx ctx, String requestId, String shortPlayKey, String at)
            throws Exception {
        return registerRaw(ctx.prefix + requestId, ctx.key(shortPlayKey), ctx.channel, at);
    }

    private ResultActions registerRaw(String requestId, String playKey, String channelId, String at)
            throws Exception {
        return postJson("/api/playout-decisions", registerBody(requestId, playKey, channelId, at));
    }

    private static Map<String, Object> registerBody(String requestId, String playKey,
                                                    String channelId, String at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("playKey", playKey);
        body.put("channelId", channelId);
        body.put("at", at);
        return body;
    }

    private ResultActions submitReceipt(Ctx ctx, String shortPlayKey, String requestId,
                                        String result, String note) throws Exception {
        return submitReceiptRaw(ctx.key(shortPlayKey), ctx.prefix + requestId, result, note);
    }

    private ResultActions submitReceiptRaw(String playKey, String requestId,
                                           String result, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("result", result);
        body.put("note", note);
        return postJson("/api/playout-decisions/" + playKey + "/receipts", body);
    }

    private ResultActions getDecision(Ctx ctx, String shortPlayKey) throws Exception {
        return getDecisionRaw(ctx.key(shortPlayKey));
    }

    private ResultActions getDecisionRaw(String playKey) throws Exception {
        return mvc.perform(get("/api/playout-decisions/" + playKey));
    }

    private ResultActions listDecisions(Ctx ctx, String businessDay) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/playout-decisions")
                .param("businessDay", businessDay));
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

    private interface ThrowingSupplier<T> {
        T get(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int threads, ThrowingSupplier<T> action) throws Exception {
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
}
