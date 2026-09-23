package com.example.starter.playout.failover;

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
 * 主备链路租约切换与游标回执仲裁的端到端测试。
 * 运行于 H2（MODE=MySQL）内存库，验证真实唯一约束、事务回滚与并发提交顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailoverApiTest {

    private static final String DAY = "2026-09-24";
    private static final String T_10 = "2026-09-24T10:00:00.000+08:00";
    private static final String T_1020 = "2026-09-24T10:20:00.000+08:00";
    private static final String T_11 = "2026-09-24T11:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-24T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-25T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 链路配置 ----------

    @Test
    void configureLinksInitializesActiveGenerationOneLease() throws Exception {
        String p = prefix();
        newChannel(p);

        // 404：频道不存在
        postJson("/api/failover/channels/" + p + "ghost/links",
                Map.of("primaryLinkId", p + "a", "backupLinkId", p + "b"))
                .andExpect(status().isNotFound());
        // 400：主备 linkId 相同
        postJson("/api/failover/channels/" + p + "ch/links",
                Map.of("primaryLinkId", p + "same", "backupLinkId", p + "same"))
                .andExpect(status().isBadRequest());

        // 主流程：配置成功并初始化 generation=1 的 ACTIVE 主链路租约
        postJson("/api/failover/channels/" + p + "ch/links",
                Map.of("primaryLinkId", p + "primary", "backupLinkId", p + "backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("BACKUP"))
                .andExpect(jsonPath("$[1].role").value("PRIMARY"))
                .andExpect(jsonPath("$[1].linkId").value(p + "primary"))
                .andExpect(jsonPath("$[1].healthy").value(true));

        // 409：重复配置
        postJson("/api/failover/channels/" + p + "ch/links",
                Map.of("primaryLinkId", p + "primary", "backupLinkId", p + "backup"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LINKS_ALREADY_CONFIGURED"));

        JsonNode state = readState(p + "ch");
        assertThat(state.get("activeLease").get("generation").asLong()).isEqualTo(1L);
        assertThat(state.get("activeLease").get("linkId").asText()).isEqualTo(p + "primary");
        assertThat(state.get("activeLease").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(state.get("activeLease").get("scheduleSnapshot").isNull());
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
    }

    // ---------- 安全切换主流程 ----------

    @Test
    void safeCutoverEndsSourceAndStartsIncrementedGeneration() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        // 无未决插播，双方缓存一致
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2, 3, 4, 5);
        sendCached(p + "ch", p + "backup", 1, 1, 2, 3, 4, 5);

        // 预览：公共前缀 5，下一条 6，无缺口、无落后，不落任何数据
        JsonNode preview = readJson(postJson("/api/failover/failovers/preview",
                activateBody("req-preview", "fo-" + p, p + "ch", version,
                        p + "primary", p + "backup", 5, 5, T_10))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.get("commonPrefixSequence").asLong()).isEqualTo(5L);
        assertThat(preview.get("nextSequence").asLong()).isEqualTo(6L);
        assertThat(preview.get("lag").asLong()).isZero();
        assertThat(preview.get("gaps").size()).isZero();
        assertThat(preview.get("targetHealthy").asBoolean()).isTrue();
        assertThat(preview.get("scheduleMatched").asBoolean()).isTrue();
        assertThat(preview.get("overridesSynced").asBoolean()).isTrue();

        // 激活：世代递增到 2，冻结切点 5
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-act", "fo-" + p, p + "ch", version,
                        p + "primary", p + "backup", 5, 5, T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.cutSequence").value(5));

        JsonNode state = readState(p + "ch");
        assertThat(state.get("activeLease").get("generation").asLong()).isEqualTo(2L);
        assertThat(state.get("activeLease").get("linkId").asText()).isEqualTo(p + "backup");
        assertThat(state.get("activeLease").get("cutSequence").asLong()).isEqualTo(5L);
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        assertThat(endedGenerations(p + "ch")).containsExactly(1L);
        // 冻结证据：编排版本 {"2026-09-24":1}、空插播栈
        assertThat(state.get("activeLease").get("scheduleSnapshot").asText())
                .contains("\"" + DAY + "\":1");
        assertThat(state.get("activeLease").get("overrideSnapshot").asText()).isEqualTo("[]");
        // 切换使频道版本再 +1
        assertThat(state.get("channelVersion").asLong()).isEqualTo(version + 1);
    }

    // ---------- 旧世代回执与重复回执仲裁 ----------

    @Test
    void oldGenerationReceiptsAreLateAndDuplicatesSettleOnce() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2, 3, 4, 5);
        sendCached(p + "ch", p + "backup", 1, 1, 2, 3, 4, 5);
        activate(p, "fo-" + p, version, 5, 5);

        // 新链路（gen2）从切点 5 的下一条 6 续播，公开游标推进到 6
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("CURRENT"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));

        // 旧链路提交旧世代（gen1）回执：只保存为 LATE，不推进频道游标
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "primary", 1, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));

        // 旧世代回执重放：仍为 LATE，不重复落库
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "primary", 1, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));

        // 新链路重复回执只结算一次：游标仍为 6，不重复落库
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("CURRENT"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));

        // 新链路跳号（缺 7 直接 8）：禁止跳过未播内容
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 8))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEIPT_OUT_OF_ORDER"));

        // 连续补 7 后游标到 7，旧世代回执仍不影响
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedSequence").value(7));

        JsonNode state = readState(p + "ch");
        assertThat(state.get("activeLease").get("confirmedSequence").asLong()).isEqualTo(7L);
        assertThat(state.get("lateReceipts")).hasSize(1);
        assertThat(state.get("lateReceipts").get(0).get("generation").asLong()).isEqualTo(1L);
        assertThat(state.get("lateReceipts").get(0).get("sequence").asLong()).isEqualTo(6L);
        assertThat(countReceipts(p + "ch", p + "primary", 1, 6)).isEqualTo(1);
        assertThat(countReceipts(p + "ch", p + "backup", 2, 6)).isEqualTo(1);
    }

    // ---------- 缺口回滚 ----------

    @Test
    void gapInTargetCacheRollsBackWithoutLeaseOrOrderChange() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2, 3, 4);
        // 目标缓存乱序：缺 3 却有 4（3 为内部缺口）
        sendCached(p + "ch", p + "backup", 1, 1, 2, 4);

        JsonNode preview = readJson(postJson("/api/failover/failovers/preview",
                activateBody("req-preview", "fo-" + p, p + "ch", version,
                        p + "primary", p + "backup", 4, 4, T_10))
                .andExpect(status().isOk()).andReturn());
        assertThat(preview.get("gaps")).hasSize(1);
        assertThat(preview.get("gaps").get(0).asLong()).isEqualTo(3L);

        // 激活 422：整单回滚，无新租约、无切换单，仍只有 gen1 ACTIVE
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-gap", "fo-" + p, p + "ch", version,
                        p + "primary", p + "backup", 4, 4, T_10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GAP_IN_TARGET_CACHE"));

        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        assertThat(readState(p + "ch").get("activeLease").get("generation").asLong()).isEqualTo(1L);
        assertThat(countOrders("fo-" + p)).isZero();

        // 失败不占 requestId：同一 requestId 在补齐缺口后可成功
        sendCached(p + "ch", p + "backup", 1, 3);
        activate(p, "fo-" + p + "-ok", version, 4, 4, "req-" + p + "-gap");
        assertThat(readState(p + "ch").get("activeLease").get("generation").asLong()).isEqualTo(2L);
    }

    // ---------- 各 422 / 409 失败分支 ----------

    @Test
    void unhealthyLagAndVersionMismatchRejectActivation() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);

        // 目标不健康
        reportState(p, p + "backup", false, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1);
        activateExpect(p, version, 1, 0, "TARGET_UNHEALTHY", 422);
        reportState(p, p + "backup", true, 1L, "");

        // 编排版本不一致
        reportState(p, p + "backup", true, 9L, "");
        activateExpect(p, version, 1, 0, "SCHEDULE_VERSION_MISMATCH", 422);
        reportState(p, p + "backup", true, 1L, "");

        // 落后超过上限（源 34、目标 0，lag=34>32）
        for (long seq = 1; seq <= 34; seq++) {
            postJson("/api/failover/receipts", receiptBody(p + "ch", p + "primary", 1, seq))
                    .andExpect(status().isOk());
        }
        activateExpect(p, version, 34, 0, "TARGET_LAG_TOO_LARGE", 422);

        // 目标补齐到连续 33（lag=1<=32，无缺口）后可激活，切点冻结为公共前缀 33
        for (long seq = 1; seq <= 33; seq++) {
            postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 1, seq))
                    .andExpect(status().isOk());
        }
        activate(p, "fo-" + p + "-lagok", version, 34, 33);
        assertThat(readState(p + "ch").get("activeLease").get("generation").asLong()).isEqualTo(2L);
        assertThat(readState(p + "ch").get("activeLease").get("cutSequence").asLong()).isEqualTo(33L);
    }

    @Test
    void staleChannelVersionAndMovedLeaseConflict() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2);
        sendCached(p + "ch", p + "backup", 1, 1, 2);

        // 编排再次发布使频道版本前移，提交旧版本 → 409（复用 draftVersion=1 再次发布）
        publishAgain(p, 1L);
        long newVersion = version + 1;
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-stale", "fo-" + p, p + "ch", version,
                        p + "primary", p + "backup", 2, 2, T_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CHANNEL_VERSION_CONFLICT"));

        // 用最新版本可成功
        reportState(p, p + "backup", true, 2L, "");
        activate(p, "fo-" + p, newVersion, 2, 2);
    }

    // ---------- 未决紧急插播同步 ----------

    @Test
    void pendingEmergencyOverrideMustBeSyncedBeforeCutover() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        long grantId = lastGrantId;

        // 未同步签名：目标 sig 为空而频道有未决插播 → 422
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2, 3);
        sendCached(p + "ch", p + "backup", 1, 1, 2, 3);

        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov",
                        "overrideKey", "ov-" + p,
                        "channelId", p + "ch",
                        "assetId", p + "movie",
                        "grantId", grantId,
                        "priority", 5,
                        "start", T_10,
                        "end", T_1020))
                .andExpect(status().isOk());
        long versionAfterOverride = version + 1;

        activateExpect(p, versionAfterOverride, 3, 3, "OVERRIDE_NOT_SYNCED", 422);

        // 从只读状态接口取当前插播栈签名，目标同步后激活成功
        String signature = readState(p + "ch").get("evidence").get("overrideSignature").asText();
        assertThat(signature).contains("ov-" + p);
        reportState(p, p + "backup", true, 1L, signature);
        activate(p, "fo-" + p, versionAfterOverride, 3, 3);
        JsonNode state = readState(p + "ch");
        assertThat(state.get("activeLease").get("generation").asLong()).isEqualTo(2L);
        assertThat(state.get("activeLease").get("overrideSnapshot").asText())
                .contains("ov-" + p);
        assertThat(state.get("evidence").get("activeOverrideKeys"))
                .extracting(j -> j.asText()).contains("ov-" + p);
    }

    // ---------- 幂等与 failoverKey 唯一 ----------

    @Test
    void activationIdempotencyAndFailoverKeyUniqueness() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2);
        sendCached(p + "ch", p + "backup", 1, 1, 2);

        Map<String, Object> body = activateBody("req-" + p + "-idem", "fo-" + p, p + "ch", version,
                p + "primary", p + "backup", 2, 2, T_10);
        postJson("/api/failover/failovers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2));
        // 同参重放：返回首次快照，世代不再递增
        postJson("/api/failover/failovers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2));
        assertThat(readState(p + "ch").get("activeLease").get("generation").asLong()).isEqualTo(2L);

        // 同 requestId 异参：409
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-idem", "fo-" + p + "-other", p + "ch", version,
                        p + "primary", p + "backup", 2, 2, T_11))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // failoverKey 唯一：新 requestId 复用旧键 → 409
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-dup", "fo-" + p, p + "ch", version + 1,
                        p + "backup", p + "primary", 2, 2, T_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("FAILOVER_KEY_CONFLICT"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentFailoversNeverProduceTwoActiveLeases() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2);
        sendCached(p + "ch", p + "backup", 1, 1, 2);

        // 两个切换单并发：一个正向 primary->backup，一个反向 backup->primary（同一快照版本）
        reportState(p, p + "primary", true, 1L, "");
        List<Integer> statuses = runConcurrently(2, i -> {
            String source = i == 0 ? p + "primary" : p + "backup";
            String target = i == 0 ? p + "backup" : p + "primary";
            return mvc.perform(post("/api/failover/failovers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(activateBody("req-" + p + "-c" + i, "fo-" + p + "-c" + i,
                                    p + "ch", version, source, target, 2, 2, T_10))))
                    .andReturn().getResponse().getStatus();
        });
        // 行锁串行化后：恰一个成功，另一个因源租约已变化/版本变化 409，绝不出现两条 ACTIVE
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        List<Long> generations = leaseGenerations(p + "ch");
        assertThat(generations).containsExactly(1L, 2L);
        assertThat(countOrdersByChannel(p + "ch")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateReceiptSettlesOnce() throws Exception {
        String p = prefix();
        setupPublishedChannel(p);

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(
                        post("/api/failover/receipts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(receiptBody(p + "ch", p + "primary", 1, 1))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(countReceipts(p + "ch", p + "primary", 1, 1)).isEqualTo(1);
        assertThat(readState(p + "ch").get("activeLease").get("confirmedSequence").asLong())
                .isEqualTo(1L);
    }

    @Test
    void previewNeverPersistsAndReverseFailoverKeepsCursorContinuous() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2, 3);
        sendCached(p + "ch", p + "backup", 1, 1, 2, 3);

        // 连续预览两次：只读，绝不落租约/切换单
        for (int i = 0; i < 2; i++) {
            postJson("/api/failover/failovers/preview",
                    activateBody("req-preview-" + i, "fo-preview-" + i, p + "ch", version,
                            p + "primary", p + "backup", 3, 3, T_10))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nextSequence").value(4));
        }
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        assertThat(countOrdersByChannel(p + "ch")).isZero();
        assertThat(readState(p + "ch").get("activeLease").get("generation").asLong()).isEqualTo(1L);

        // 第一次切换 primary -> backup（gen2，切点 3）
        activate(p, "fo-" + p + "-1", version, 3, 3);
        // 新链路续播 4、5
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 4))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedSequence").value(4));
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 5))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmedSequence").value(5));

        // 原主链路追上后反向切回：primary 缓存 gen2 的 4、5
        long versionAfterFirst = version + 1;
        reportState(p, p + "primary", true, 1L, "");
        sendCached(p + "ch", p + "primary", 2, 4, 5);
        postJson("/api/failover/failovers",
                activateBody("req-" + p + "-back", "fo-" + p + "-2", p + "ch", versionAfterFirst,
                        p + "backup", p + "primary", 5, 5, T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(3))
                .andExpect(jsonPath("$.cutSequence").value(5));

        // 跨世代公开游标连续：从切点 5 续播 6；旧 gen2 链路迟到回执记 LATE 不回退游标
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "primary", 3, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("CURRENT"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));
        postJson("/api/failover/receipts", receiptBody(p + "ch", p + "backup", 2, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.confirmedSequence").value(6));

        JsonNode state = readState(p + "ch");
        assertThat(state.get("activeLease").get("generation").asLong()).isEqualTo(3L);
        assertThat(state.get("activeLease").get("linkId").asText()).isEqualTo(p + "primary");
        assertThat(state.get("activeLease").get("confirmedSequence").asLong()).isEqualTo(6L);
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        assertThat(leaseGenerations(p + "ch")).containsExactly(1L, 2L, 3L);
    }

    @Test
    void receiptConcurrentWithFailoverFollowsCommitOrderWithoutBreakingCursor() throws Exception {
        String p = prefix();
        long version = setupPublishedChannel(p);
        reportState(p, p + "backup", true, 1L, "");
        sendCurrent(p + "ch", p + "primary", 1, 1, 2);
        sendCached(p + "ch", p + "backup", 1, 1, 2);

        // 激活（快照源位置 2）与源链路新回执 sequence=3 并发，按提交顺序：
        //  - 切换先提交：回执 3 落在 gen1 旧世代 -> LATE，游标由新租约从切点 2 续播；
        //  - 回执先提交：激活重验发现源位置 3 != 2 -> 409。
        List<Integer> results = runConcurrently(2, i -> {
            if (i == 0) {
                return mvc.perform(post("/api/failover/failovers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(activateBody("req-" + p + "-cf", "fo-" + p + "-cf",
                                        p + "ch", version, p + "primary", p + "backup",
                                        2, 2, T_10))))
                        .andReturn().getResponse().getStatus();
            }
            return mvc.perform(post("/api/failover/receipts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(receiptBody(p + "ch", p + "primary", 1, 3))))
                    .andReturn().getResponse().getStatus();
        });
        // 两种提交顺序都合法：切换为 200 或 409，回执恒为 200；绝不出现两条 ACTIVE 或游标倒退。
        assertThat(results.get(1)).isEqualTo(200);
        assertThat(results.get(0)).isIn(200, 409);
        assertThat(activeLeaseCount(p + "ch")).isEqualTo(1);
        long activeGeneration = readState(p + "ch").get("activeLease").get("generation").asLong();
        assertThat(activeGeneration).isIn(1L, 2L);
        long cursor = readState(p + "ch").get("activeLease").get("confirmedSequence").asLong();
        assertThat(cursor).isGreaterThanOrEqualTo(2L);
    }

    // ---------- 辅助 ----------

    private long lastGrantId;

    /** 创建素材、频道、授权、主备链路、草稿并发布一次；返回发布后的频道版本。 */
    private long setupPublishedChannel(String p) throws Exception {
        newChannel(p);
        postJson("/api/failover/channels/" + p + "ch/links",
                Map.of("primaryLinkId", p + "primary", "backupLinkId", p + "backup"))
                .andExpect(status().isOk());
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", p + "seg-1");
        segment.put("assetId", p + "movie");
        segment.put("start", T_10);
        segment.put("end", T_11);
        putJson("/api/channels/" + p + "ch/drafts/" + DAY,
                Map.of("requestId", "req-" + p + "-draft", "expectedDraftVersion", 0,
                        "segments", List.of(segment)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + p + "ch/drafts/" + DAY + "/publish",
                Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
        return readState(p + "ch").get("channelVersion").asLong();
    }

    private void newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        MvcResult grant = postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk()).andReturn();
        lastGrantId = readJson(grant).get("id").asLong();
    }

    private void publishAgain(String p, long draftVersion) throws Exception {
        postJson("/api/channels/" + p + "ch/drafts/" + DAY + "/publish",
                Map.of("requestId", "req-" + p + "-pub2", "draftVersion", draftVersion,
                        "expectedPublishedVersion", 1))
                .andExpect(status().isOk());
    }
    private void reportState(String p, String linkId, boolean healthy, long cachedVersion,
                             String signature) throws Exception {
        putJson("/api/failover/channels/" + p + "ch/links/" + linkId + "/state",
                Map.of("healthy", healthy, "cachedScheduleVersion", cachedVersion,
                        "cachedOverrideSignature", signature))
                .andExpect(status().isOk());
    }

    private void sendCurrent(String channel, String link, long generation, long... seqs)
            throws Exception {
        sendReceipts(channel, link, generation, seqs);
    }

    private void sendCached(String channel, String link, long generation, long... seqs)
            throws Exception {
        sendReceipts(channel, link, generation, seqs);
    }

    private void sendReceipts(String channel, String link, long generation, long[] seqs)
            throws Exception {
        for (long seq : seqs) {
            postJson("/api/failover/receipts", receiptBody(channel, link, generation, seq))
                    .andExpect(status().isOk());
        }
    }

    private void activate(String p, String failoverKey, long channelVersion,
                          long sourceLast, long targetLast) throws Exception {
        activate(p, failoverKey, channelVersion, sourceLast, targetLast,
                "req-" + failoverKey);
    }

    private void activate(String p, String failoverKey, long channelVersion,
                          long sourceLast, long targetLast, String requestId) throws Exception {
        postJson("/api/failover/failovers",
                activateBody(requestId, failoverKey, p + "ch", channelVersion,
                        p + "primary", p + "backup", sourceLast, targetLast, T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"));
    }

    private void activateExpect(String p, long channelVersion, long sourceLast, long targetLast,
                                String errorCode, int httpStatus) throws Exception {
        var result = postJson("/api/failover/failovers",
                activateBody("req-" + p + "-rej-" + errorKey(errorCode), "fo-" + p + "-" + errorKey(errorCode),
                        p + "ch", channelVersion, p + "primary", p + "backup",
                        sourceLast, targetLast, T_10));
        if (httpStatus == 422) {
            result.andExpect(status().isUnprocessableEntity());
        } else {
            result.andExpect(status().isConflict());
        }
        result.andExpect(jsonPath("$.error").value(errorCode));
    }

    private static String errorKey(String code) {
        return code.toLowerCase();
    }

    private Map<String, Object> receiptBody(String channel, String link, long generation,
                                            long sequence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channel);
        body.put("linkId", link);
        body.put("generation", generation);
        body.put("sequence", sequence);
        return body;
    }

    private Map<String, Object> activateBody(String requestId, String failoverKey, String channel,
                                             long channelVersion, String source, String target,
                                             long sourceLast, long targetLast, String cutoverAt) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("failoverKey", failoverKey);
        order.put("channelId", channel);
        order.put("channelVersion", channelVersion);
        order.put("sourceLinkId", source);
        order.put("targetLinkId", target);
        order.put("sourceLastSequence", sourceLast);
        order.put("targetLastSequence", targetLast);
        order.put("cutoverAt", cutoverAt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("order", order);
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode readState(String channel) throws Exception {
        return readJson(mvc.perform(get("/api/failover/channels/" + channel + "/state"))
                .andExpect(status().isOk()).andReturn());
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int activeLeaseCount(String channel) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM playout_lease WHERE channel_id = ? AND status = 'ACTIVE'",
                Integer.class, channel);
        return count == null ? 0 : count;
    }

    private List<Long> endedGenerations(String channel) {
        return jdbc.queryForList(
                "SELECT generation FROM playout_lease WHERE channel_id = ? AND status = 'ENDED'"
                        + " ORDER BY generation", Long.class, channel);
    }

    private List<Long> leaseGenerations(String channel) {
        return jdbc.queryForList(
                "SELECT generation FROM playout_lease WHERE channel_id = ? ORDER BY generation",
                Long.class, channel);
    }

    private int countReceipts(String channel, String link, long generation, long sequence) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM playout_receipt"
                + " WHERE channel_id = ? AND link_id = ? AND generation = ? AND sequence_no = ?",
                Integer.class, channel, link, generation, sequence);
        return count == null ? 0 : count;
    }

    private int countOrders(String failoverKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM playout_failover_order WHERE failover_key = ?",
                Integer.class, failoverKey);
        return count == null ? 0 : count;
    }

    private int countOrdersByChannel(String channel) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM playout_failover_order WHERE channel_id = ?",
                Integer.class, channel);
        return count == null ? 0 : count;
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
