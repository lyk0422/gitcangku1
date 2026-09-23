package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 主备播出链路租约切换 API 端到端测试：安全切换、缺口回滚、旧代回执、幂等重放与并发边界。
 * 运行环境为真实 H2（MODE=MySQL）内存库：唯一索引（单 ACTIVE 租约）、行锁与事务提交顺序
 * 均由真实数据库验证，不使用 mock 或内存 Map 代替。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailoverApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String CUTOVER = "2026-09-22T10:30:00.000+08:00";
    private static final String OV_START = "2026-09-22T10:20:00.000+08:00";
    private static final String OV_END = "2026-09-22T10:40:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：安全切换、世代递增、切点冻结 ----------

    @Test
    void safeFailoverSwitchesLeaseAndFreezesCutpoint() throws Exception {
        Ctx ctx = newContext(5, 5);

        // 预览：公共前缀 5，下一条应播 6，无缺口
        postJson("/api/failover-orders/preview", previewBody(ctx, 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commonPrefixSeq").value(5))
                .andExpect(jsonPath("$.nextSeq").value(6))
                .andExpect(jsonPath("$.gaps").isArray())
                .andExpect(jsonPath("$.gaps.length()").value(0))
                .andExpect(jsonPath("$.targetHealthy").value(true))
                .andExpect(jsonPath("$.targetCachedVersion").value(1))
                .andExpect(jsonPath("$.channelVersion").value(1));
        // 预览不写数据：租约仍是主链路世代 1
        getJson("/api/channels/" + ctx.channel + "/lease")
                .andExpect(jsonPath("$.linkId").value(ctx.primary))
                .andExpect(jsonPath("$.generation").value(1));

        // 创建切换单并激活
        String key = ctx.prefix + "order-1";
        createOrder(ctx, key, 5, 5, CUTOVER, 10, "req-" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        activate(key, "req-" + key + "-act")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.safeCutSeq").value(6))
                .andExpect(jsonPath("$.scheduleVersion").value(1))
                .andExpect(jsonPath("$.activeLease.linkId").value(ctx.backup))
                .andExpect(jsonPath("$.activeLease.generation").value(2))
                .andExpect(jsonPath("$.activeLease.confirmedSeq").value(5))
                .andExpect(jsonPath("$.activeLease.cutoverSeq").value(6))
                .andExpect(jsonPath("$.scheduleEvidence.channelVersion").value(1))
                .andExpect(jsonPath("$.scheduleEvidence.targetCachedVersion").value(1));

        // 数据库硬约束：全频道恰好一条 ACTIVE 租约，世代为 2
        assertThat(activeLeaseCount(ctx.channel)).isEqualTo(1);
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.backup);
        assertThat(maxGeneration(ctx.channel)).isEqualTo(2);

        // 旧世代回执（源链路 gen=1 迟到 seq=6）：只存档 LATE，不推进游标
        receipt(ctx.channel, ctx.primary, 1, 6, "req-late-6")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(5))
                .andExpect(jsonPath("$.activeGeneration").value(2));

        // 新链路 gen=2 seq=6：SETTLED，游标推进到 6
        receipt(ctx.channel, ctx.backup, 2, 6, "req-set-6")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(6));

        // 查询切换单：迟到回执可见，切点与世代只读
        getJson("/api/failover-orders/" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateReceipts.length()").value(1))
                .andExpect(jsonPath("$.lateReceipts[0].linkId").value(ctx.primary))
                .andExpect(jsonPath("$.lateReceipts[0].seq").value(6))
                .andExpect(jsonPath("$.lateReceipts[0].generation").value(1));
    }

    // ---------- 新链路回执：重复只结算一次、跳跃被拒 ----------

    @Test
    void duplicateReceiptSettlesOnceAndJumpRejected() throws Exception {
        Ctx ctx = newContext(3, 3);
        String key = ctx.prefix + "order-dup";
        createOrder(ctx, key, 3, 3, CUTOVER, 10, "req-" + key).andExpect(status().isOk());
        activate(key, "req-" + key + "-act").andExpect(status().isOk());

        // 切点前 seq 的回执：DUPLICATE，游标不变
        receipt(ctx.channel, ctx.backup, 2, 1, "req-dup-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(3));
        // 同 requestId 重放：首次快照
        receipt(ctx.channel, ctx.backup, 2, 1, "req-dup-1")
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"));
        // 正常结算 seq=4
        receipt(ctx.channel, ctx.backup, 2, 4, "req-set-4")
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(4));
        // 重复投递已结算 seq：DUPLICATE，只结算一次
        receipt(ctx.channel, ctx.backup, 2, 4, "req-dup-4")
                .andExpect(jsonPath("$.disposition").value("DUPLICATE"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(4));
        // 跳跃 seq=6（缺 5）：422，不推进游标
        receipt(ctx.channel, ctx.backup, 2, 6, "req-jump-6")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEIPT_OUT_OF_ORDER"));
        // 未来世代：409
        receipt(ctx.channel, ctx.backup, 9, 5, "req-future-5")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("UNKNOWN_GENERATION"));
    }

    // ---------- 失败分支：缺口回滚、目标不健康、落后超限 ----------

    @Test
    void gapAtActivationRejectsAndLeaseUntouchedThenHeals() throws Exception {
        // 备链路缺 seq=3：公共前缀为 2，缺口 [3]
        Ctx ctx = newContextWithBackupSeqs(5, List.of(1, 2, 4, 5));
        postJson("/api/failover-orders/preview", previewBody(ctx, 10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commonPrefixSeq").value(2))
                .andExpect(jsonPath("$.nextSeq").value(3))
                .andExpect(jsonPath("$.gaps.length()").value(1))
                .andExpect(jsonPath("$.gaps[0]").value(3));

        String key = ctx.prefix + "order-gap";
        // 创建单允许（监控员留痕），提交的 targetLastSeq=5 为真实最大 seq
        createOrder(ctx, key, 5, 5, CUTOVER, 10, "req-" + key).andExpect(status().isOk());
        // 激活：存在缺口，422 整单回滚
        activate(key, "req-" + key + "-act1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEIPT_GAP"));
        // 失败不占键：同一 failoverKey 的单仍是 CREATED，租约世代/持有者不变
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.primary);
        assertThat(maxGeneration(ctx.channel)).isEqualTo(1);
        getJson("/api/failover-orders/" + key)
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.generation").isEmpty());

        // 备链路补回执 seq=3（STANDBY 存档），缺口消除后激活成功
        receipt(ctx.channel, ctx.backup, 1, 3, "req-fill-3")
                .andExpect(jsonPath("$.disposition").value("STANDBY"));
        activate(key, "req-" + key + "-act2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.safeCutSeq").value(6));
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.backup);
    }

    @Test
    void targetUnhealthyBlocksActivation() throws Exception {
        Ctx ctx = newContext(2, 2);
        // 创建单之后目标上报不健康
        String key = ctx.prefix + "order-sick";
        createOrder(ctx, key, 2, 2, CUTOVER, 10, "req-" + key).andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/links/" + ctx.backup + "/health",
                        healthBody(ctx.channel, false, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false));
        activate(key, "req-" + key + "-act")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TARGET_NOT_HEALTHY"));
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.primary);
    }

    @Test
    void targetLagExceededBlocksActivation() throws Exception {
        Ctx ctx = newContext(5, 2);
        String key = ctx.prefix + "order-lag";
        createOrder(ctx, key, 5, 2, CUTOVER, 2, "req-" + key).andExpect(status().isOk());
        // 源已确认 5、目标连续前缀 2，落后 3 条 > 上限 2
        activate(key, "req-" + key + "-act")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TARGET_LAG_EXCEEDED"));
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.primary);

        // 落后恰在上限内可激活
        String key2 = ctx.prefix + "order-lag2";
        createOrder(ctx, key2, 5, 2, CUTOVER, 3, "req-" + key2).andExpect(status().isOk());
        activate(key2, "req-" + key2 + "-act").andExpect(status().isOk());
    }

    // ---------- 失败分支：缓存版本落后 / 频道版本变化 ----------

    @Test
    void schedulePublishBetweenCreateAndActivateConflicts() throws Exception {
        Ctx ctx = newContext(3, 3);
        String key = ctx.prefix + "order-pub";
        createOrder(ctx, key, 3, 3, CUTOVER, 10, "req-" + key).andExpect(status().isOk());

        // 之间发布新版编排（schedule_version 1 -> 2）：激活按提交顺序 409
        republish(ctx, "req-repub");
        activate(key, "req-" + key + "-act")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CHANNEL_VERSION_CONFLICT"));
        // 原租约不动
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.primary);

        // 目标追上缓存版本 2 后用新单激活成功，冻结版本为 2
        postJson("/api/channels/" + ctx.channel + "/links/" + ctx.backup + "/health",
                healthBody(ctx.channel, true, 2)).andExpect(status().isOk());
        String key2 = ctx.prefix + "order-pub2";
        createOrder(ctx, key2, 3, 3, CUTOVER, 10, "req-" + key2, 2)
                .andExpect(jsonPath("$.channelVersion").value(2));
        activate(key2, "req-" + key2 + "-act")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleVersion").value(2))
                .andExpect(jsonPath("$.activeLease.scheduleVersion").value(2));
    }

    @Test
    void staleTargetCacheRejectedAtCreate() throws Exception {
        Ctx ctx = newContext(2, 2);
        // 新版发布后目标仍缓存版本 1：创建单即 422
        republish(ctx, "req-repub-stale");
        postJson("/api/failover-orders", orderBody(ctx, ctx.prefix + "stale", 2, 2, CUTOVER, 2,
                        "req-stale-order", 2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TARGET_CACHE_STALE"));
    }

    // ---------- 未决紧急插播必须同步，激活时冻结插播栈 ----------

    @Test
    void pendingOverrideMustSyncAndStackFrozen() throws Exception {
        Ctx ctx = newContext(4, 4);
        createEmergencyOverride(ctx, "ov-news", "req-ov-create");
        String key = ctx.prefix + "order-ov";
        createOrder(ctx, key, 4, 4, CUTOVER, 10, "req-" + key).andExpect(status().isOk());

        // 未同步：422
        activate(key, "req-" + key + "-act1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("OVERRIDE_NOT_SYNCED"));

        // 同步到目标后激活成功，插播栈被冻结
        mvc.perform(post("/api/channels/" + ctx.channel + "/links/" + ctx.backup
                        + "/overrides/" + ctx.prefix + "ov-news/synced"))
                .andExpect(status().isOk());
        activate(key, "req-" + key + "-act2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frozenStack.length()").value(1))
                .andExpect(jsonPath("$.frozenStack[0].overrideKey").value(ctx.prefix + "ov-news"))
                .andExpect(jsonPath("$.frozenStack[0].assetId").value(ctx.movie));
        // 只读查询从冻结的 JSON 快照列还原插播栈与编排证据
        getJson("/api/failover-orders/" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.frozenStack.length()").value(1))
                .andExpect(jsonPath("$.frozenStack[0].overrideKey").value(ctx.prefix + "ov-news"))
                .andExpect(jsonPath("$.frozenStack[0].priority").value(5))
                .andExpect(jsonPath("$.scheduleEvidence.publicationVersions[0]").value(1))
                .andExpect(jsonPath("$.activeLease.cutoverSeq").value(5));
    }

    // ---------- 幂等：同参重放、异参 409、失败不占键、key 唯一 ----------

    @Test
    void idempotencySemantics() throws Exception {
        // 源已确认 3、备连续到 2：落后 1 条，用于区分 maxLag=0 失败与 maxLag=10 成功
        Ctx ctx = newContext(3, 2);
        String key = ctx.prefix + "order-idem";

        Map<String, Object> body = orderBody(ctx, key, 3, 2, CUTOVER, 10, "req-create-idem", 1);
        postJson("/api/failover-orders", body).andExpect(status().isOk());
        // 同 requestId 同参重放：返回首次快照
        postJson("/api/failover-orders", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failoverKey").value(key))
                .andExpect(jsonPath("$.status").value("CREATED"));
        // 同 requestId 异参：409
        postJson("/api/failover-orders", orderBody(ctx, key, 3, 2, CUTOVER, 9, "req-create-idem", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // failoverKey 唯一：新 requestId 复用 key 也是 409
        postJson("/api/failover-orders", orderBody(ctx, key, 3, 2, CUTOVER, 10, "req-create-other", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_FAILOVER_KEY"));

        // 激活：先在 key2（maxLag=0，落后 1 条）上失败，失败不占 requestId，同参重试仍失败
        String key2 = ctx.prefix + "order-idem2";
        postJson("/api/failover-orders", orderBody(ctx, key2, 3, 2, CUTOVER, 0, "req-create-idem2", 1))
                .andExpect(status().isOk());
        activate(key2, "req-act-idem")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TARGET_LAG_EXCEEDED"));
        activate(key2, "req-act-idem")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("TARGET_LAG_EXCEEDED"));
        // 在 key（maxLag=10）上激活成功
        activate(key, "req-act-shared")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        // 同 requestId 同参重放：首次快照
        activate(key, "req-act-shared")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
        // 同 requestId 用于另一张单：409
        activate(key2, "req-act-shared")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 连续两次切换（回切）：世代链与无空窗/无双活 ----------

    @Test
    void failBackToPrimaryAdvancesGenerationChain() throws Exception {
        Ctx ctx = newContext(5, 5);
        String key1 = ctx.prefix + "order-fb1";
        createOrder(ctx, key1, 5, 5, CUTOVER, 10, "req-" + key1).andExpect(status().isOk());
        activate(key1, "req-" + key1 + "-act")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2));

        // 新持约链路 backup 在世代 2 结算 6、7
        receipt(ctx.channel, ctx.backup, 2, 6, "req-g2-6")
                .andExpect(jsonPath("$.disposition").value("SETTLED"));
        receipt(ctx.channel, ctx.backup, 2, 7, "req-g2-7")
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(7));
        // 原主链路作为备链路缓存世代 2 的 6、7（STANDBY）
        receipt(ctx.channel, ctx.primary, 2, 6, "req-g2p-6")
                .andExpect(jsonPath("$.disposition").value("STANDBY"));
        receipt(ctx.channel, ctx.primary, 2, 7, "req-g2p-7")
                .andExpect(jsonPath("$.disposition").value("STANDBY"));

        // 回切 backup -> primary
        Map<String, Object> back = new LinkedHashMap<>();
        back.put("requestId", "req-order-fb2");
        back.put("failoverKey", ctx.prefix + "order-fb2");
        back.put("channelId", ctx.channel);
        back.put("channelVersion", 1);
        back.put("sourceLinkId", ctx.backup);
        back.put("targetLinkId", ctx.primary);
        back.put("sourceLastSeq", 7);
        back.put("targetLastSeq", 7);
        back.put("cutoverAt", CUTOVER);
        back.put("maxLag", 10);
        postJson("/api/failover-orders", back)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceLinkId").value(ctx.backup));
        activate(ctx.prefix + "order-fb2", "req-order-fb2-act")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(3))
                .andExpect(jsonPath("$.safeCutSeq").value(8))
                .andExpect(jsonPath("$.activeLease.linkId").value(ctx.primary))
                .andExpect(jsonPath("$.activeLease.confirmedSeq").value(7));

        // 世代 3 之后世代 1 的更晚回执仍是 LATE；两条 ENDED + 一条 ACTIVE 共存
        receipt(ctx.channel, ctx.backup, 1, 7, "req-late-g1-7")
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(7));
        assertThat(activeLeaseCount(ctx.channel)).isEqualTo(1);
        assertThat(activeLeaseLink(ctx.channel)).isEqualTo(ctx.primary);
        assertThat(maxGeneration(ctx.channel)).isEqualTo(3);
        Integer ended = jdbc.queryForObject("SELECT COUNT(1) FROM playout_link_lease"
                + " WHERE channel_id = ? AND status = 'ENDED'", Integer.class, ctx.channel);
        assertThat(ended).isEqualTo(2);
    }

    // ---------- 并发：两张单同时激活，只有一张生效，无双活/无空窗 ----------

    @Test
    void concurrentActivationsOnlyOneWins() throws Exception {
        Ctx ctx = newContext(4, 4);
        String key1 = ctx.prefix + "order-c1";
        String key2 = ctx.prefix + "order-c2";
        createOrder(ctx, key1, 4, 4, CUTOVER, 10, "req-" + key1).andExpect(status().isOk());
        createOrder(ctx, key2, 4, 4, CUTOVER, 10, "req-" + key2).andExpect(status().isOk());

        List<Integer> statuses = runConcurrently(2, i -> {
            String k = i == 0 ? key1 : key2;
            return mvc.perform(post("/api/failover-orders/" + k + "/activate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("requestId", "req-act-c" + i))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 只有一条 ACTIVE 租约，世代只递增一次
        assertThat(activeLeaseCount(ctx.channel)).isEqualTo(1);
        assertThat(maxGeneration(ctx.channel)).isEqualTo(2);
    }

    @Test
    void concurrentSameSeqReceiptsSettleOnce() throws Exception {
        Ctx ctx = newContext(0, 0);
        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(
                        post("/api/channels/" + ctx.channel + "/links/" + ctx.primary + "/receipts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(receiptBody(ctx.channel, 1, 1, "req-rcpt" + i))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        getJson("/api/channels/" + ctx.channel + "/lease")
                .andExpect(jsonPath("$.confirmedSeq").value(1));
        // 同链路同世代同 seq 只有一条记录
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM playout_link_receipt"
                + " WHERE channel_id = ? AND link_id = ? AND generation = 1 AND seq = 1",
                Integer.class, ctx.channel, ctx.primary);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 回执裁决：备链路 STANDBY，源链路游标连续 ----------

    @Test
    void standbyReceiptsDoNotMoveCursorAndSourceMustBeContiguous() throws Exception {
        Ctx ctx = newContext(0, 0);
        // 备链路先来回执：STANDBY，频道游标不动
        receipt(ctx.channel, ctx.backup, 1, 1, "req-std-1")
                .andExpect(jsonPath("$.disposition").value("STANDBY"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(0));
        // 源链路跳跃：422
        receipt(ctx.channel, ctx.primary, 1, 2, "req-src-2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEIPT_OUT_OF_ORDER"));
        // 源链路按序结算
        receipt(ctx.channel, ctx.primary, 1, 1, "req-src-1")
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(1));
        receipt(ctx.channel, ctx.primary, 1, 2, "req-src-2b")
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.channelConfirmedSeq").value(2));
    }

    // ---------- 注册 / 健康 / 引导的失败分支 ----------

    @Test
    void linkRegistrationHealthAndBootstrapBranches() throws Exception {
        String p = prefix();
        postJson("/api/assets", Map.of("id", p + "fb", "durationMs", 30000)).andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fb"))
                .andExpect(status().isOk());

        // 404：频道不存在
        putJson("/api/channels/" + p + "ghost/links", Map.of("linkId", p + "l1", "role", "PRIMARY"))
                .andExpect(status().isNotFound());
        // 注册主/备
        putJson("/api/channels/" + p + "ch/links", Map.of("linkId", p + "l1", "role", "PRIMARY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.cachedVersion").value(0));
        putJson("/api/channels/" + p + "ch/links", Map.of("linkId", p + "l2", "role", "BACKUP"))
                .andExpect(status().isOk());
        // 409：重复链路 / 角色冲突
        putJson("/api/channels/" + p + "ch/links", Map.of("linkId", p + "l1", "role", "PRIMARY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_LINK"));
        putJson("/api/channels/" + p + "ch/links", Map.of("linkId", p + "l3", "role", "PRIMARY"))
                .andExpect(status().isConflict());
        // 404：健康上报链路不存在
        postJson("/api/channels/" + p + "ch/links/" + p + "ghost/health",
                        Map.of("channelId", p + "ch", "healthy", true, "cachedVersion", 0))
                .andExpect(status().isNotFound());
        // 缓存版本回退：409
        postJson("/api/channels/" + p + "ch/links/" + p + "l1/health",
                healthBody(p + "ch", true, 1)).andExpect(status().isOk());
        postJson("/api/channels/" + p + "ch/links/" + p + "l1/health",
                healthBody(p + "ch", true, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CACHED_VERSION_REGRESS"));

        // 引导租约
        postJson("/api/channels/" + p + "ch/lease/bootstrap", Map.of("linkId", p + "l1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.linkId").value(p + "l1"));
        // 重复引导：409
        postJson("/api/channels/" + p + "ch/lease/bootstrap", Map.of("linkId", p + "l2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_ALREADY_ACTIVE"));
        // 源不是持约链路不能建切换单（该频道未发布编排，channelVersion=0）
        postJson("/api/failover-orders", Map.of(
                        "requestId", "req-bad-src", "failoverKey", p + "fk",
                        "channelId", p + "ch", "channelVersion", 0,
                        "sourceLinkId", p + "l2", "targetLinkId", p + "l1",
                        "sourceLastSeq", 0, "targetLastSeq", 0,
                        "cutoverAt", CUTOVER, "maxLag", 10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SOURCE_NOT_ACTIVE"));
    }

    // ---------- 测试夹具与辅助 ----------

    private String prefix;
    private long grantId;

    /** 建频道上下文：素材、授权、主备链路、发布一版编排、引导世代 1，并投递双方回执。 */
    private Ctx newContext(int sourceSeq, int backupSeq) throws Exception {
        return buildContext(sourceSeq, seqRange(1, backupSeq));
    }

    /** 建频道上下文，备链路回执序列显式指定（可构造缺口）。 */
    private Ctx newContextWithBackupSeqs(int sourceSeq, List<Integer> backupSeqs) throws Exception {
        return buildContext(sourceSeq, backupSeqs);
    }

    private record Ctx(String prefix, String channel, String fallback, String movie,
                       String primary, String backup, long grantId) {
    }

    private List<Integer> seqRange(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            list.add(i);
        }
        return list;
    }

    private Ctx buildContext(int sourceSeq, List<Integer> backupSeqs) throws Exception {
        String p = prefix();
        this.prefix = p;
        String channel = p + "ch";
        String primary = p + "primary";
        String backup = p + "backup";
        postJson("/api/assets", Map.of("id", p + "fb", "durationMs", 30000)).andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", channel, "fallbackAssetId", p + "fb"))
                .andExpect(status().isOk());
        MvcResult grant = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk()).andReturn();
        long gid = readJson(grant).get("id").asLong();
        this.grantId = gid;

        putJson("/api/channels/" + channel + "/links", Map.of("linkId", primary, "role", "PRIMARY"))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/links", Map.of("linkId", backup, "role", "BACKUP"))
                .andExpect(status().isOk());

        // 发布一版编排：schedule_version 0 -> 1
        putJson("/api/channels/" + channel + "/drafts/" + DAY, replaceDraftBody(p + "d1", 0,
                        segment(p + "seg1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", p + "pub1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        postJson("/api/channels/" + channel + "/links/" + primary + "/health",
                healthBody(channel, true, 1)).andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/links/" + backup + "/health",
                healthBody(channel, true, 1)).andExpect(status().isOk());

        postJson("/api/channels/" + channel + "/lease/bootstrap", Map.of("linkId", primary))
                .andExpect(status().isOk());

        for (int s = 1; s <= sourceSeq; s++) {
            receipt(channel, primary, 1, s, p + "pr-" + s)
                    .andExpect(jsonPath("$.disposition").value("SETTLED"));
        }
        for (int s : backupSeqs) {
            receipt(channel, backup, 1, s, p + "bk-" + s)
                    .andExpect(jsonPath("$.disposition").value("STANDBY"));
        }
        return new Ctx(p, channel, p + "fb", p + "movie", primary, backup, gid);
    }

    private ResultActions createOrder(Ctx ctx, String key, long sourceLast, long targetLast,
                                      String cutoverAt, long maxLag, String requestId)
            throws Exception {
        return createOrder(ctx, key, sourceLast, targetLast, cutoverAt, maxLag, requestId, 1);
    }

    private ResultActions createOrder(Ctx ctx, String key, long sourceLast, long targetLast,
                                      String cutoverAt, long maxLag, String requestId,
                                      long channelVersion) throws Exception {
        return postJson("/api/failover-orders",
                orderBody(ctx, key, sourceLast, targetLast, cutoverAt, maxLag, requestId,
                        channelVersion));
    }

    private Map<String, Object> orderBody(Ctx ctx, String key, long sourceLast, long targetLast,
                                          String cutoverAt, long maxLag, String requestId,
                                          long channelVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("failoverKey", key);
        body.put("channelId", ctx.channel);
        body.put("channelVersion", channelVersion);
        body.put("sourceLinkId", ctx.primary);
        body.put("targetLinkId", ctx.backup);
        body.put("sourceLastSeq", sourceLast);
        body.put("targetLastSeq", targetLast);
        body.put("cutoverAt", cutoverAt);
        body.put("maxLag", maxLag);
        return body;
    }

    private Map<String, Object> previewBody(Ctx ctx, long maxLag) {
        return Map.of("channelId", ctx.channel, "sourceLinkId", ctx.primary,
                "targetLinkId", ctx.backup, "maxLag", maxLag);
    }

    private Map<String, Object> healthBody(String channel, boolean healthy, long cachedVersion) {
        return Map.of("channelId", channel, "healthy", healthy, "cachedVersion", cachedVersion);
    }

    private Map<String, Object> receiptBody(String channel, long generation, long seq,
                                            String requestId) {
        return Map.of("requestId", requestId, "channelId", channel,
                "generation", generation, "seq", seq);
    }

    private ResultActions receipt(String channel, String link, long generation, long seq,
                                  String requestId) throws Exception {
        return postJson("/api/channels/" + channel + "/links/" + link + "/receipts",
                receiptBody(channel, generation, seq, requestId));
    }

    private ResultActions activate(String key, String requestId) throws Exception {
        return postJson("/api/failover-orders/" + key + "/activate",
                Map.of("requestId", requestId));
    }

    private void republish(Ctx ctx, String requestId) throws Exception {
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        replaceDraftBody(ctx.prefix + "d2", 1,
                                segment(ctx.prefix + "seg2", ctx.movie, T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", requestId, "draftVersion", 2,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isOk());
    }

    private void createEmergencyOverride(Ctx ctx, String ovSuffix, String requestId) throws Exception {
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", requestId,
                        "overrideKey", ctx.prefix + ovSuffix,
                        "channelId", ctx.channel,
                        "assetId", ctx.movie,
                        "grantId", ctx.grantId,
                        "priority", 5,
                        "start", OV_START,
                        "end", OV_END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private int activeLeaseCount(String channel) {
        Integer c = jdbc.queryForObject("SELECT COUNT(1) FROM playout_link_lease"
                + " WHERE channel_id = ? AND status = 'ACTIVE'", Integer.class, channel);
        return c == null ? 0 : c;
    }

    private String activeLeaseLink(String channel) {
        return jdbc.queryForObject("SELECT link_id FROM playout_link_lease"
                + " WHERE channel_id = ? AND status = 'ACTIVE'", String.class, channel);
    }

    private long maxGeneration(String channel) {
        Long g = jdbc.queryForObject("SELECT COALESCE(MAX(generation), 0) FROM playout_link_lease"
                + " WHERE channel_id = ?", Long.class, channel);
        return g == null ? 0 : g;
    }

    private static Map<String, Object> segment(String id, String assetId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> replaceDraftBody(String requestId, long expectedVersion,
                                                        Map<String, Object> segment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segment));
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "f" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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
