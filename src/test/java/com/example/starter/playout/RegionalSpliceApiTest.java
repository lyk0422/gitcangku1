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
 * 区域插播替换与授权版本原子固化测试：覆盖区域窗口校验、授权覆盖、整次发布 422、
 * 快照冻结、spliceKey 幂等与并发边界。运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionalSpliceApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_10_15 = "2026-09-22T10:15:00.000+08:00";
    private static final String T_10_30 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_10_45 = "2026-09-22T10:45:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String REGION_SH = "CN-SH";
    private static final String REGION_BJ = "CN-BJ";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 插播配置：窗口与授权校验 ----------

    @Test
    void spliceConfigWindowAndGrantValidation() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        // 区域授权尚未创建：插播素材无覆盖该区域的授权 → 422
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s0", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 区域授权仅覆盖 CN-BJ：对 CN-SH 插播不生效 → 422
        createGrant(channel, p + "promo", REGION_BJ);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 区域授权覆盖 CN-SH：配置成功并回显插播
        createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s2", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].splices[0].regionCode").value(REGION_SH))
                .andExpect(jsonPath("$.segments[0].splices[0].assetId").value(p + "promo"))
                .andExpect(jsonPath("$.segments[0].splices[0].start").value(T_10_15));

        // 422：同条目同区域窗口重叠
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s3", 1,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45),
                                        splice(REGION_SH, p + "promo", T_10_30, T_11))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_WINDOW_OVERLAP"));

        // 端点相接合法：10:15-10:30 与 10:30-10:45
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s4", 1,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_30),
                                        splice(REGION_SH, p + "promo", T_10_30, T_10_45))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].splices.length()").value(2));

        // 422：插播窗口超出条目窗口
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s5", 2,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_45,
                                                "2026-09-22T11:30:00.000+08:00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_WINDOW_OUT_OF_SEGMENT"));

        // 422：插播素材已撤回
        postJson("/api/assets", Map.of("id", p + "old", "durationMs", 1000))
                .andExpect(status().isOk());
        createGrant(channel, p + "old", REGION_SH);
        postJson("/api/assets/" + p + "old/withdraw", Map.of("requestId", "req-" + p + "-wd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-s6", 2,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "old", T_10_15, T_10_45))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ASSET_WITHDRAWN"));
    }

    // ---------- 素材撤回 ----------

    @Test
    void assetWithdrawIdempotencyAndErrors() throws Exception {
        String p = prefix();
        newChannel(p);

        postJson("/api/assets/" + p + "movie/withdraw", Map.of("requestId", "req-" + p + "-w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        // 幂等重放：同 requestId 同参数返回原结果
        postJson("/api/assets/" + p + "movie/withdraw", Map.of("requestId", "req-" + p + "-w1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        // 新 requestId 重复撤回：409
        postJson("/api/assets/" + p + "movie/withdraw", Map.of("requestId", "req-" + p + "-w2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ASSET_ALREADY_WITHDRAWN"));
        // 404：素材不存在
        postJson("/api/assets/" + p + "ghost/withdraw", Map.of("requestId", "req-" + p + "-w3"))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布：区域解析、快照固化与冻结 ----------

    @Test
    void publishResolvesRegionsAndSnapshotFrozen() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        long promoGrant = createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        // 发布主流程：响应携带 spliceKey 与区域快照行
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0, "spliceKey", "sk-" + p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.spliceKey").value("sk-" + p))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 快照查询：固化区域、条目、实际素材、授权版本、插播窗口与回退原因
        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spliceKey").value("sk-" + p))
                .andExpect(jsonPath("$.segments[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.regions[0].regionCode").value(REGION_SH))
                .andExpect(jsonPath("$.regions[0].assetId").value(p + "promo"))
                .andExpect(jsonPath("$.regions[0].grantId").value(promoGrant))
                .andExpect(jsonPath("$.regions[0].spliceStart").value(T_10_15))
                .andExpect(jsonPath("$.regions[1].assetId").value(p + "movie"))
                .andExpect(jsonPath("$.regions[1].fallbackReason").value("NO_SPLICE_WINDOW"));

        // 区域决策：窗口内命中插播快照素材，窗口外回退主素材
        regionPlayout(channel, REGION_SH, T_10_30)
                .andExpect(jsonPath("$.source").value("SPLICE"))
                .andExpect(jsonPath("$.assetId").value(p + "promo"))
                .andExpect(jsonPath("$.grantId").value(promoGrant))
                .andExpect(jsonPath("$.spliceStart").value(T_10_15))
                .andExpect(jsonPath("$.spliceEnd").value(T_10_45));
        regionPlayout(channel, REGION_SH, "2026-09-22T10:50:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"))
                .andExpect(jsonPath("$.spliceFallbackReason").value("NO_SPLICE_WINDOW"));
        // 未配置插播的区域：主素材 + NO_SPLICE_CONFIG
        regionPlayout(channel, REGION_BJ, T_10_30)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"))
                .andExpect(jsonPath("$.spliceFallbackReason").value("NO_SPLICE_CONFIG"));

        // 发布后修改插播配置（不重新发布）：窗口内决策仍使用发布快照素材
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d2", 1,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "movie", T_10_15, T_10_45))))
                .andExpect(status().isOk());
        regionPlayout(channel, REGION_SH, T_10_30)
                .andExpect(jsonPath("$.source").value("SPLICE"))
                .andExpect(jsonPath("$.assetId").value(p + "promo"));

        // 授权撤销：历史快照不被改写，区域决策回退主素材并标注原因
        postJson("/api/grants/" + promoGrant + "/revoke", Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk());
        getJson("/api/publications/" + publicationId)
                .andExpect(jsonPath("$.regions[0].assetId").value(p + "promo"))
                .andExpect(jsonPath("$.regions[0].grantId").value(promoGrant));
        regionPlayout(channel, REGION_SH, T_10_30)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"))
                .andExpect(jsonPath("$.spliceFallbackReason").value("SPLICE_GRANT_REVOKED"));

        // 素材撤回：历史快照同样不被改写
        postJson("/api/assets/" + p + "promo/withdraw", Map.of("requestId", "req-" + p + "-wd"))
                .andExpect(status().isOk());
        getJson("/api/publications/" + publicationId)
                .andExpect(jsonPath("$.regions[0].assetId").value(p + "promo"));

        // 404：快照不存在
        getJson("/api/publications/999999")
                .andExpect(status().isNotFound());
    }

    // ---------- 发布阻断：授权失效 / 素材撤回 / 黑屏相交，整次 422 ----------

    @Test
    void publishBlockedAtomicallyWithStableDetails() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        long promoGrant = createGrant(channel, p + "promo", REGION_SH);
        createGrant(channel, p + "promo", REGION_BJ);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45),
                                        splice(REGION_BJ, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        // 撤销 CN-SH 插播授权 → 诊断列出阻断，整次发布 422 且稳定列出区域和原因
        postJson("/api/grants/" + promoGrant + "/revoke", Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/splice-diagnostics")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftVersion").value(1))
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].regionCode").value(REGION_SH))
                .andExpect(jsonPath("$.blocks[0].reason").value("GRANT_INVALID"));

        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_BLOCKED"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].regionCode").value(REGION_SH))
                .andExpect(jsonPath("$.details[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.details[0].reason").value("GRANT_INVALID"));

        // 不发布部分区域：CN-BJ 插播有效，但整次发布被拒绝，无任何快照
        regionPlayout(channel, REGION_BJ, T_10_30)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 补充 CN-SH 授权后发布成功（失败请求未占用任何版本）
        createGrant(channel, p + "promo", REGION_SH);
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.regions.length()").value(4));
    }

    @Test
    void publishBlockedByWithdrawnAssetAndBlackout() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        // 黑屏窗口与插播窗口相交 → 整次 422
        postJson("/api/channels/" + channel + "/blackout-windows",
                        Map.of("requestId", "req-" + p + "-b1", "regionCode", REGION_SH,
                                "start", T_10_30, "end", T_11))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value(REGION_SH));
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_BLOCKED"))
                .andExpect(jsonPath("$.details[0].reason").value("BLACKOUT_INTERSECT"));

        // 黑屏窗口端点相接（10:45 起）不相交 → 另一频道验证发布成功
        String p2 = prefix();
        String channel2 = newChannel(p2);
        createGrant(channel2, p2 + "promo", REGION_SH);
        putJson("/api/channels/" + channel2 + "/drafts/" + DAY,
                        draftBody("req-" + p2 + "-d1", 0,
                                segment(p2 + "seg-1", p2 + "movie", T_10, T_11,
                                        splice(REGION_SH, p2 + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel2 + "/blackout-windows",
                        Map.of("requestId", "req-" + p2 + "-b1", "regionCode", REGION_SH,
                                "start", T_10_45, "end", T_11))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel2 + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p2 + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 素材撤回 → 整次 422（诊断同样列出）
        postJson("/api/assets/" + p + "promo/withdraw", Map.of("requestId", "req-" + p + "-wd"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/splice-diagnostics")
                .andExpect(jsonPath("$.blocks[0].reason").value("ASSET_WITHDRAWN"));
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason").value("ASSET_WITHDRAWN"));

        // 黑屏窗口接口：400 区间非法；幂等重放；404 频道不存在
        postJson("/api/channels/" + channel + "/blackout-windows",
                        Map.of("requestId", "req-" + p + "-b2", "start", T_11, "end", T_10))
                .andExpect(status().isBadRequest());
        postJson("/api/channels/" + channel + "/blackout-windows",
                        Map.of("requestId", "req-" + p + "-b1", "regionCode", REGION_SH,
                                "start", T_10_30, "end", T_11))
                .andExpect(status().isOk());
        postJson("/api/channels/" + p + "ghost/blackout-windows",
                        Map.of("requestId", "req-" + p + "-b3", "start", T_10, "end", T_11))
                .andExpect(status().isNotFound());
    }

    // ---------- spliceKey 幂等：同键重放首次完整快照，失败不占键 ----------

    @Test
    void spliceKeyIdempotencyReplayAndFailureNotOccupying() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        // 首次发布
        MvcResult first = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0, "spliceKey", "sk-" + p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(first).get("publicationId").asLong();

        // 同键同内容（新 requestId）：重放首次完整快照，不产生新版本
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 1, "spliceKey", "sk-" + p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.regions.length()").value(2));

        // 失败不占键：版本冲突的发布失败后，同键修正参数可成功
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p3", "draftVersion", 1,
                                "expectedPublishedVersion", 9, "spliceKey", "sk2-" + p))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p4", "draftVersion", 1,
                                "expectedPublishedVersion", 1, "spliceKey", "sk2-" + p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2));

        // 同键不同内容：草稿变更后指纹不同 → 409
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d2", 1,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_30))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p5", "draftVersion", 2,
                                "expectedPublishedVersion", 2, "spliceKey", "sk-" + p))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 并发：发布与授权撤销按提交顺序裁决 ----------

    @Test
    void concurrentPublishAndGrantRevokeResolvedByCommitOrder() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        long promoGrant = createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        List<Integer> results = runConcurrently(2, i -> {
            if (i == 0) {
                return mvc.perform(post("/api/channels/" + channel + "/drafts/" + DAY + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + p + "-pub",
                                        "draftVersion", 1, "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus();
            }
            return mvc.perform(post("/api/grants/" + promoGrant + "/revoke")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("requestId", "req-" + p + "-rv"))))
                    .andReturn().getResponse().getStatus();
        });
        int publishStatus = results.get(0);
        // 撤销始终成功；发布按提交顺序要么成功要么 422，绝不部分发布
        assertThat(results.get(1)).isEqualTo(200);
        assertThat(publishStatus).isIn(200, 422);

        MvcResult decision = regionPlayout(channel, REGION_SH, T_10_30)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = readJson(decision);
        if (publishStatus == 200) {
            // 发布先提交：快照存在且不被撤销改写；决策应用撤销结果回退主素材
            assertThat(body.get("source").asText()).isEqualTo("PROGRAM");
            assertThat(body.get("spliceFallbackReason").asText()).isEqualTo("SPLICE_GRANT_REVOKED");
            getJson("/api/publications/" + body.get("publicationId").asLong())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.regions[0].assetId").value(p + "promo"));
        } else {
            // 撤销先提交：整次发布 422，无任何区域快照
            assertThat(body.get("source").asText()).isEqualTo("FALLBACK");
            assertThat(body.get("reason").asText()).isEqualTo("NO_PUBLISHED_SCHEDULE");
        }
    }

    // ---------- 并发：同 spliceKey 发布仅固化一次 ----------

    @Test
    void concurrentSameSpliceKeyPublishOnlyOneSnapshot() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createGrant(channel, p + "promo", REGION_SH);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11,
                                        splice(REGION_SH, p + "promo", T_10_15, T_10_45))))
                .andExpect(status().isOk());

        List<MvcResult> results = runConcurrentlyResults(2, i ->
                mvc.perform(post("/api/channels/" + channel + "/drafts/" + DAY + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + p + "-p" + i,
                                        "draftVersion", 1, "expectedPublishedVersion", 0,
                                        "spliceKey", "sk-" + p))))
                        .andReturn());
        assertThat(results).allSatisfy(r ->
                assertThat(r.getResponse().getStatus()).isEqualTo(200));
        long firstPublication = readJson(results.get(0)).get("publicationId").asLong();
        assertThat(readJson(results.get(1)).get("publicationId").asLong())
                .isEqualTo(firstPublication);
        // 仅固化一次：发布版本仍为 1，下一版本可正常发布
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-px", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2));
    }

    // ---------- 测试辅助 ----------

    /** 创建频道、主素材（含全天授权）与插播素材，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "promo", "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        createGrant(p + "ch", p + "movie", null);
        return p + "ch";
    }

    private long createGrant(String channel, String asset, String regionCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channel);
        body.put("assetId", asset);
        if (regionCode != null) {
            body.put("regionCode", regionCode);
        }
        body.put("validFrom", GRANT_FROM);
        body.put("validTo", GRANT_TO);
        MvcResult result = postJson("/api/grants", body)
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> splice(String regionCode, String assetId,
                                              String start, String end) {
        Map<String, Object> splice = new LinkedHashMap<>();
        splice.put("regionCode", regionCode);
        splice.put("assetId", assetId);
        splice.put("start", start);
        splice.put("end", end);
        return splice;
    }

    private static Map<String, Object> segment(String id, String assetId, String start, String end,
                                               Map<String, Object>... splices) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        if (splices.length > 0) {
            segment.put("splices", List.of(splices));
        }
        return segment;
    }

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 Map<String, Object>... segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segments));
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

    private ResultActions regionPlayout(String channel, String regionCode, String at)
            throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/regions/" + regionCode + "/playout")
                .param("at", at));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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

    private interface ThrowingResultSupplier {
        MvcResult get(int index) throws Exception;
    }

    private static List<MvcResult> runConcurrentlyResults(int threads, ThrowingResultSupplier action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
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
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}
