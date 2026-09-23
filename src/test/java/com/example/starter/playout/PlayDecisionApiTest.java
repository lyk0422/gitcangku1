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
 * 播出决定固化登记与回执 API 端到端测试：主流程、幂等、失败回滚、撤销/取消竞争与并发边界。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 * requestId 全局唯一，各用例统一以 ctx.key(...) 生成带上下文前缀的请求 ID。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayDecisionApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_1130 = "2026-09-22T11:30:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：节目决定登记、明细与列表查询 ----------

    @Test
    void registerProgramDecisionMainFlow() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        register(ctx, "req-reg-1", "pk-1", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playKey").value(ctx.key("pk-1")))
                .andExpect(jsonPath("$.channelId").value(ctx.channel))
                .andExpect(jsonPath("$.playAt").value(T_1030))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.overrideKey").isEmpty())
                .andExpect(jsonPath("$.publicationId").isNumber())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segmentId").value(ctx.key("seg-1")))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant))
                .andExpect(jsonPath("$.requestId").value(ctx.key("req-reg-1")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.receiptStatus").value("PENDING"))
                .andExpect(jsonPath("$.receipt").isEmpty());

        // 明细查询与登记响应一致
        getDecision(ctx, "pk-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant))
                .andExpect(jsonPath("$.receiptStatus").value("PENDING"));

        // 列表查询包含该记录
        listDecisions(ctx)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].playKey").value(ctx.key("pk-1")));

        // 原实时查询保持只读且结果一致
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset));
    }

    @Test
    void registerEmergencyAndFallbackDecisions() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);
        createOverride(ctx, "req-ov", "ov-1", 5, T_1020, T_1040);

        // 命中紧急插播：EMERGENCY 来源，记录 overrideKey 与实际采用授权
        register(ctx, "req-em", "pk-em", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant))
                .andExpect(jsonPath("$.publicationId").isEmpty())
                .andExpect(jsonPath("$.segmentId").isEmpty());

        // 节目空档：FALLBACK + GAP，保底无授权 grantId 为 null
        register(ctx, "req-gap", "pk-gap", T_1130)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.grantId").isEmpty());

        // 无已发布编排：FALLBACK + NO_PUBLISHED_SCHEDULE
        Ctx bare = newContext();
        register(bare, "req-nopub", "pk-nopub", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(bare.fallbackAsset))
                .andExpect(jsonPath("$.grantId").isEmpty());
    }

    // ---------- 与撤销/取消竞争：按提交顺序裁决 ----------

    @Test
    void revokeBeforeRegistrationFreezesFallback() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        // 撤销先提交：登记不能固化为仍有效的 PROGRAM，而是当时的 GRANT_REVOKED 保底
        revokeGrant(ctx, ctx.movieGrant, "req-rv");
        register(ctx, "req-reg", "pk-1", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.publicationId").isNumber())
                .andExpect(jsonPath("$.segmentId").value(ctx.key("seg-1")))
                .andExpect(jsonPath("$.grantId").isEmpty());
    }

    @Test
    void registrationBeforeRevokeKeepsProgramAndReceiptStillAccepted() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        // 固化先提交：保留当时 PROGRAM 结果
        register(ctx, "req-reg", "pk-1", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"));

        // 之后撤销授权：实时查询变为保底，但固化记录不变
        revokeGrant(ctx, ctx.movieGrant, "req-rv");
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));

        // 授权撤销不阻止对已固化记录提交回执
        submitReceipt(ctx, "pk-1", "req-rc", "PLAYED", "已正常播出")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PLAYED"));
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"));
    }

    @Test
    void registrationBeforeCancelKeepsEmergency() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);
        createOverride(ctx, "req-ov", "ov-1", 5, T_1020, T_1040);

        register(ctx, "req-reg", "pk-1", T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"));

        // 取消插播后实时查询回到节目，固化记录仍为 EMERGENCY
        postJson("/api/emergency-overrides/" + ctx.key("ov-1") + "/cancel",
                        Map.of("requestId", ctx.key("req-cancel")))
                .andExpect(status().isOk());
        playout(ctx, T_1030).andExpect(jsonPath("$.source").value("PROGRAM"));
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.grantId").value(ctx.newsGrant));
    }

    // ---------- 登记幂等与 playKey 去重 ----------

    @Test
    void registrationIdempotencyAndPlayKeyDedup() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        Map<String, Object> body = registerBody(ctx.key("req-1"), ctx.key("pk-1"), ctx.channel, T_1030);
        MvcResult first = postJson("/api/play-decisions", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andReturn();
        String createdAt = readJson(first).get("createdAt").asText();

        // 同 requestId 同参数重放：返回首次响应
        postJson("/api/play-decisions", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(ctx.key("req-1")))
                .andExpect(jsonPath("$.createdAt").value(createdAt));
        // 同 requestId 改时刻：409
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-1"), ctx.key("pk-1"), ctx.channel, T_1040))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改 playKey：409
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-1"), ctx.key("pk-2"), ctx.channel, T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 撤销授权后，不同 requestId 以相同 playKey 和相同频道、时刻登记：
        // 返回原决定（仍为 PROGRAM，不重新计算、不覆盖）
        revokeGrant(ctx, ctx.movieGrant, "req-rv");
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-2"), ctx.key("pk-1"), ctx.channel, T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.requestId").value(ctx.key("req-1")))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andExpect(jsonPath("$.grantId").value(ctx.movieGrant));
        // 不同 requestId 同 playKey 改时刻：409
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-3"), ctx.key("pk-1"), ctx.channel, T_1040))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAY_KEY_CONFLICT"));

        // 失败不占 requestId：频道不存在 404 后，同 requestId 可成功复用
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-4"), ctx.key("pk-9"), ctx.prefix + "ghost", T_1030))
                .andExpect(status().isNotFound());
        postJson("/api/play-decisions",
                        registerBody(ctx.key("req-4"), ctx.key("pk-9"), ctx.channel, T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playKey").value(ctx.key("pk-9")));
    }

    // ---------- 回执规则 ----------

    @Test
    void receiptLifecycleAndConflicts() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);
        register(ctx, "req-reg", "pk-1", T_1030).andExpect(status().isOk());
        register(ctx, "req-reg-2", "pk-2", T_1040).andExpect(status().isOk());

        // 无回执时 PENDING
        getDecision(ctx, "pk-1").andExpect(jsonPath("$.receiptStatus").value("PENDING"));

        // 首次回执固化结果与时刻
        submitReceipt(ctx, "pk-1", "rc-1", PLAYED, "正常播出")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playKey").value(ctx.key("pk-1")))
                .andExpect(jsonPath("$.result").value("PLAYED"))
                .andExpect(jsonPath("$.note").value("正常播出"))
                .andExpect(jsonPath("$.requestId").value(ctx.key("rc-1")))
                .andExpect(jsonPath("$.receiptAt").isNotEmpty());
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.receipt.note").value("正常播出"))
                .andExpect(jsonPath("$.receipt.requestId").value(ctx.key("rc-1")));

        // 同 requestId 同参重放：返回首次回执
        submitReceipt(ctx, "pk-1", "rc-1", PLAYED, "正常播出")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(ctx.key("rc-1")));
        // 同 requestId 改说明：409
        submitReceipt(ctx, "pk-1", "rc-1", PLAYED, "改说明")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 不同 requestId 提交相同结果与说明：返回已有回执（保留首次 requestId）
        submitReceipt(ctx, "pk-1", "rc-2", PLAYED, "正常播出")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(ctx.key("rc-1")));
        // 不同 requestId 改说明：409
        submitReceipt(ctx, "pk-1", "rc-3", PLAYED, "其他说明")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECEIPT_CONFLICT"));
        // 不同 requestId 改结果：409
        submitReceipt(ctx, "pk-1", "rc-4", FAILED, "正常播出")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECEIPT_CONFLICT"));

        // 另一条决定可独立提交 FAILED 回执
        submitReceipt(ctx, "pk-2", "rc-5", FAILED, "播出失败")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("FAILED"));
        getDecision(ctx, "pk-2").andExpect(jsonPath("$.receiptStatus").value("FAILED"));

        // 400：非法结果值（仅接受 PLAYED/FAILED）
        postJson("/api/play-decisions/" + ctx.key("pk-2") + "/receipt",
                        receiptBody(ctx.key("rc-6"), "BROKEN", "说明"))
                .andExpect(status().isBadRequest());
        // 400：说明为空
        postJson("/api/play-decisions/" + ctx.key("pk-2") + "/receipt",
                        receiptBody(ctx.key("rc-7"), PLAYED, " "))
                .andExpect(status().isBadRequest());
        // 404：决定不存在；失败不占 requestId，同键随后用于合法决定时按回执内容裁决而非幂等冲突
        postJson("/api/play-decisions/" + ctx.prefix + "ghost/receipt",
                        receiptBody(ctx.key("rc-8"), PLAYED, "说明"))
                .andExpect(status().isNotFound());
        submitReceipt(ctx, "pk-2", "rc-8", PLAYED, "说明")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RECEIPT_CONFLICT"));
    }

    // ---------- 参数校验与 404 ----------

    @Test
    void validationAndNotFoundBranches() throws Exception {
        Ctx ctx = newContext();

        // 400：缺少 playKey / at / requestId
        Map<String, Object> noKey = registerBody(ctx.key("v-1"), ctx.key("pk"), ctx.channel, T_1030);
        noKey.remove("playKey");
        postJson("/api/play-decisions", noKey).andExpect(status().isBadRequest());
        Map<String, Object> noAt = registerBody(ctx.key("v-2"), ctx.key("pk"), ctx.channel, T_1030);
        noAt.remove("at");
        postJson("/api/play-decisions", noAt).andExpect(status().isBadRequest());
        Map<String, Object> noReq = registerBody(ctx.key("v-3"), ctx.key("pk"), ctx.channel, T_1030);
        noReq.remove("requestId");
        postJson("/api/play-decisions", noReq).andExpect(status().isBadRequest());

        // 404：频道不存在 / 决定不存在 / 列表频道不存在
        postJson("/api/play-decisions",
                        registerBody(ctx.key("v-5"), ctx.key("pk-g"), ctx.prefix + "ghost", T_1030))
                .andExpect(status().isNotFound());
        getDecisionRaw(ctx.prefix + "ghost").andExpect(status().isNotFound());
        mvc.perform(get("/api/play-decisions")
                        .param("channelId", ctx.prefix + "ghost")
                        .param("businessDay", DAY))
                .andExpect(status().isNotFound());
        // 400：业务日格式非法
        mvc.perform(get("/api/play-decisions")
                        .param("channelId", ctx.channel)
                        .param("businessDay", "2026-13-40"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 列表排序：播出时刻、playKey ----------

    @Test
    void listSortedByPlayAtThenPlayKey() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        register(ctx, "r1", "k3", T_1040).andExpect(status().isOk());
        register(ctx, "r2", "k2", T_1030).andExpect(status().isOk());
        register(ctx, "r3", "k1", T_1030).andExpect(status().isOk());

        MvcResult result = listDecisions(ctx).andExpect(status().isOk()).andReturn();
        JsonNode list = readJson(result);
        assertThat(list).hasSize(3);
        // 同时刻按 playKey 升序，整体按播出时刻升序
        assertThat(list.get(0).get("playKey").asText()).isEqualTo(ctx.key("k1"));
        assertThat(list.get(1).get("playKey").asText()).isEqualTo(ctx.key("k2"));
        assertThat(list.get(2).get("playKey").asText()).isEqualTo(ctx.key("k3"));
        assertThat(list.get(0).get("receiptStatus").asText()).isEqualTo("PENDING");
    }

    // ---------- 并发：同 playKey 登记只产生一份决定 ----------

    @Test
    void concurrentRegistrationSamePlayKeyProducesOneDecision() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(post("/api/play-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(
                                ctx.key("cc-req-" + i), ctx.key("pk-cc"), ctx.channel, T_1030))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactly(200, 200);

        // 只产生一份决定
        MvcResult list = listDecisions(ctx).andExpect(status().isOk()).andReturn();
        assertThat(readJson(list)).hasSize(1);
        getDecision(ctx, "pk-cc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"));
    }

    // ---------- 并发：并发回执只产生一份 ----------

    @Test
    void concurrentReceiptsProduceOneReceipt() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "seg-1", T_10, T_11);
        register(ctx, "req-reg", "pk-1", T_1030).andExpect(status().isOk());

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(
                        post("/api/play-decisions/" + ctx.key("pk-1") + "/receipt")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(receiptBody(
                                        ctx.key("cc-rc-" + i), PLAYED, "并发相同说明"))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactly(200, 200);

        // 只有一份回执，结果为首次固化内容
        getDecision(ctx, "pk-1")
                .andExpect(jsonPath("$.receiptStatus").value("PLAYED"))
                .andExpect(jsonPath("$.receipt.note").value("并发相同说明"));
    }

    // ---------- 测试辅助 ----------

    private static final String PLAYED = "PLAYED";
    private static final String FAILED = "FAILED";

    /** 预置上下文：保底素材、普通素材 movie/news、频道及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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

    /** 发布一段 movie 节目。 */
    private void publishProgram(Ctx ctx, String segmentId, String start, String end) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.key(segmentId));
        segment.put("assetId", ctx.movieAsset);
        segment.put("start", start);
        segment.put("end", end);
        segments.add(segment);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", ctx.key("draft-" + segmentId));
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", ctx.key("pub-" + segmentId),
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

    private void revokeGrant(Ctx ctx, long grantId, String requestId) throws Exception {
        postJson("/api/grants/" + grantId + "/revoke", Map.of("requestId", ctx.key(requestId)))
                .andExpect(status().isOk());
    }

    private void createOverride(Ctx ctx, String requestId, String shortKey, int priority,
                                String start, String end) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", ctx.key(requestId));
        body.put("overrideKey", ctx.key(shortKey));
        body.put("channelId", ctx.channel);
        body.put("assetId", ctx.newsAsset);
        body.put("grantId", ctx.newsGrant);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        postJson("/api/emergency-overrides", body).andExpect(status().isOk());
    }

    private ResultActions register(Ctx ctx, String requestId, String shortKey, String at)
            throws Exception {
        return postJson("/api/play-decisions",
                registerBody(ctx.key(requestId), ctx.key(shortKey), ctx.channel, at));
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

    private ResultActions submitReceipt(Ctx ctx, String shortKey, String requestId,
                                        String result, String note) throws Exception {
        return postJson("/api/play-decisions/" + ctx.key(shortKey) + "/receipt",
                receiptBody(ctx.key(requestId), result, note));
    }

    private static Map<String, Object> receiptBody(String requestId, String result, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("result", result);
        body.put("note", note);
        return body;
    }

    private ResultActions getDecision(Ctx ctx, String shortKey) throws Exception {
        return getDecisionRaw(ctx.key(shortKey));
    }

    private ResultActions getDecisionRaw(String playKey) throws Exception {
        return mvc.perform(get("/api/play-decisions/" + playKey));
    }

    private ResultActions listDecisions(Ctx ctx) throws Exception {
        return mvc.perform(get("/api/play-decisions")
                .param("channelId", ctx.channel)
                .param("businessDay", DAY));
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
