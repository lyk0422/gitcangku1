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
 * 区域插播替换与授权版本原子固化端到端测试：区域窗口、授权覆盖、整次发布、快照冻结与并发幂等。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionalSpliceApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String W1_FROM = "2026-09-22T10:10:00.000+08:00";
    private static final String W1_TO = "2026-09-22T10:20:00.000+08:00";
    private static final String W2_FROM = "2026-09-22T10:20:00.000+08:00";
    private static final String W2_TO = "2026-09-22T10:30:00.000+08:00";
    private static final String AT_IN_W1 = "2026-09-22T10:15:00.000+08:00";
    private static final String AT_OUT_W = "2026-09-22T10:45:00.000+08:00";
    private static final String NORTH = "CN-NORTH";
    private static final String SOUTH = "CN-SOUTH";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 插播配置主流程与 spliceKey 幂等 ----------

    @Test
    void spliceConfigMainFlowAndIdempotency() throws Exception {
        Ctx ctx = newContext();
        String url = spliceUrl(ctx);

        // 主流程：区域 CN-NORTH 配置一个插播窗口
        Map<String, Object> body = spliceBody("sk-" + ctx.p + "-1", 1,
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));
        putJson(url, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(ctx.channel))
                .andExpect(jsonPath("$.segmentId").value(ctx.segment))
                .andExpect(jsonPath("$.draftVersion").value(1))
                .andExpect(jsonPath("$.regions[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.regions[0].windows[0].assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.regions[0].windows[0].grantId").value(ctx.spliceGrantN))
                .andExpect(jsonPath("$.regions[0].windows[0].grantVersion").value(1))
                .andExpect(jsonPath("$.regions[0].windows[0].start").value(W1_FROM))
                .andExpect(jsonPath("$.regions[0].windows[0].end").value(W1_TO));

        // 查询当前配置
        getJson(url)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.regions[0].windows.length()").value(1));

        // 同 spliceKey 同参数重放：返回首次完整结果，不重复写入
        putJson(url, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].windows.length()").value(1));
        getJson(url).andExpect(jsonPath("$.regions[0].windows.length()").value(1));

        // 同 spliceKey 改参数：409
        putJson(url, spliceBody("sk-" + ctx.p + "-1", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W2_FROM, W2_TO))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以重叠窗口失败，再用同 spliceKey 修正成功
        putJson(url, spliceBody("sk-" + ctx.p + "-retry", 1,
                        region(NORTH,
                                window(ctx.spliceA, ctx.spliceGrantN, W1_FROM,
                                        "2026-09-22T10:25:00.000+08:00"),
                                window(ctx.spliceA, ctx.spliceGrantN, W2_FROM, W2_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_WINDOW_OVERLAP"));
        putJson(url, spliceBody("sk-" + ctx.p + "-retry", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W2_FROM, W2_TO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].windows[0].start").value(W2_FROM));

        // 整份替换：新配置替换旧配置
        putJson(url, spliceBody("sk-" + ctx.p + "-2", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)),
                        region(SOUTH, window(ctx.spliceA, ctx.spliceGrantS, W1_FROM, W1_TO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(2));
        getJson(url)
                .andExpect(jsonPath("$.regions[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.regions[1].regionCode").value(SOUTH));
    }

    // ---------- 插播配置校验分支 ----------

    @Test
    void spliceConfigValidationBranches() throws Exception {
        Ctx ctx = newContext();
        String url = spliceUrl(ctx);

        // 端点相接合法：10:10-10:20 与 10:20-10:30
        putJson(url, spliceBody("sk-" + ctx.p + "-adj", 1,
                        region(NORTH,
                                window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO),
                                window(ctx.spliceA, ctx.spliceGrantN, W2_FROM, W2_TO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].windows.length()").value(2));

        // 422：窗口超出条目窗口（起点早于条目起点）
        putJson(url, spliceBody("sk-" + ctx.p + "-out", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN,
                                "2026-09-22T09:50:00.000+08:00", W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_WINDOW_OUT_OF_SEGMENT"));

        // 400：窗口终点不大于起点
        putJson(url, spliceBody("sk-" + ctx.p + "-inv", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_TO, W1_FROM))))
                .andExpect(status().isBadRequest());

        // 422：授权未完整覆盖窗口（授权 10:30 才生效）
        long lateGrant = createGrant(ctx.channel, ctx.spliceB, NORTH,
                "2026-09-22T10:30:00.000+08:00", GRANT_TO);
        putJson(url, spliceBody("sk-" + ctx.p + "-nc", 1,
                        region(NORTH, window(ctx.spliceB, lateGrant, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"));

        // 422：授权区域不匹配（CN-SOUTH 授权用于 CN-NORTH 窗口）
        putJson(url, spliceBody("sk-" + ctx.p + "-rm", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantS, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_REGION_MISMATCH"));

        // 422：授权不属于该素材
        putJson(url, spliceBody("sk-" + ctx.p + "-nm", 1,
                        region(NORTH, window(ctx.spliceB, ctx.spliceGrantN, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));

        // 422：授权已撤销
        long revokedGrant = createGrant(ctx.channel, ctx.spliceB, NORTH, GRANT_FROM, GRANT_TO);
        postJson("/api/grants/" + revokedGrant + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());
        putJson(url, spliceBody("sk-" + ctx.p + "-rv", 1,
                        region(NORTH, window(ctx.spliceB, revokedGrant, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 404：授权不存在
        putJson(url, spliceBody("sk-" + ctx.p + "-ng", 1,
                        region(NORTH, window(ctx.spliceA, 999999L, W1_FROM, W1_TO))))
                .andExpect(status().isNotFound());

        // 404：条目不存在
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/segments/ghost/splices",
                spliceBody("sk-" + ctx.p + "-gs", 1,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO))))
                .andExpect(status().isNotFound());

        // 409：草稿版本不符
        putJson(url, spliceBody("sk-" + ctx.p + "-dv", 9,
                        region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));

        // 422：保底素材不得用于插播
        putJson(url, spliceBody("sk-" + ctx.p + "-fb", 1,
                        region(NORTH, window(ctx.fallback, ctx.spliceGrantN, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));

        // 422：插播素材已撤回
        postJson("/api/assets", Map.of("id", ctx.p + "doomed", "durationMs", 1000))
                .andExpect(status().isOk());
        long doomedGrant = createGrant(ctx.channel, ctx.p + "doomed", NORTH, GRANT_FROM, GRANT_TO);
        postJson("/api/assets/" + ctx.p + "doomed/withdraw",
                Map.of("requestId", "req-" + ctx.p + "-wd"))
                .andExpect(status().isOk());
        putJson(url, spliceBody("sk-" + ctx.p + "-wd", 1,
                        region(NORTH, window(ctx.p + "doomed", doomedGrant, W1_FROM, W1_TO))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ASSET_WITHDRAWN"));
    }

    // ---------- 发布冻结区域快照，回执使用快照素材 ----------

    @Test
    void publishFreezesRegionalSnapshotAndReceiptUsesSnapshot() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));

        // 发布主流程
        long publicationId = readPublicationId(publish(ctx, "req-" + ctx.p + "-pub", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn());

        // 快照固化：MAIN 行（回退原因 NO_SPLICE）+ SPLICE 行（窗口、素材、授权版本）
        getJson("/api/publications/" + publicationId + "/snapshot")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segments[0].segmentId").value(ctx.segment))
                .andExpect(jsonPath("$.segments[0].assetId").value(ctx.movie))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[?(@.source=='MAIN')].regionCode").value(NORTH))
                .andExpect(jsonPath("$.regions[?(@.source=='MAIN')].assetId").value(ctx.movie))
                .andExpect(jsonPath("$.regions[?(@.source=='MAIN')].fallbackReason")
                        .value("NO_SPLICE"))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].grantId")
                        .value((int) ctx.spliceGrantN))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].grantVersion").value(1))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].spliceStart").value(W1_FROM))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].spliceEnd").value(W1_TO));

        // 区域播放决策：窗口内 SPLICE，窗口外 PROGRAM，未配置区域 PROGRAM
        regionalPlayout(ctx, NORTH, AT_IN_W1)
                .andExpect(jsonPath("$.source").value("SPLICE"))
                .andExpect(jsonPath("$.assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.segmentId").value(ctx.segment))
                .andExpect(jsonPath("$.spliceStart").value(W1_FROM))
                .andExpect(jsonPath("$.grantId").value((int) ctx.spliceGrantN))
                .andExpect(jsonPath("$.grantVersion").value(1));
        regionalPlayout(ctx, NORTH, AT_OUT_W)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie));
        regionalPlayout(ctx, SOUTH, AT_IN_W1)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie));

        // 窗口内回执使用发布快照素材；同 requestId 重放返回首次回执
        MvcResult receipt = createReceipt(ctx, NORTH, "req-" + ctx.p + "-rc", AT_IN_W1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.source").value("SPLICE"))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andReturn();
        long receiptId = readJson(receipt).get("id").asLong();
        createReceipt(ctx, NORTH, "req-" + ctx.p + "-rc", AT_IN_W1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(receiptId));
        // 同 requestId 改参数：409
        createReceipt(ctx, NORTH, "req-" + ctx.p + "-rc", AT_OUT_W)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 后续插播修改与素材版本拉取不改写历史快照：窗口改到 10:20-10:30，素材版本拉取
        configureSplice(ctx, "sk-" + ctx.p + "-cfg2",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W2_FROM, W2_TO)));
        postJson("/api/assets/" + ctx.spliceA + "/pull",
                Map.of("requestId", "req-" + ctx.p + "-pull"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 快照仍为发布时固化内容
        getJson("/api/publications/" + publicationId + "/snapshot")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].spliceStart").value(W1_FROM))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].grantVersion").value(1));
        // 回执仍按快照解析：旧窗口内仍是插播素材，新配置窗口（不在快照内）不生效
        createReceipt(ctx, NORTH, "req-" + ctx.p + "-rc2", AT_IN_W1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.source").value("SPLICE"));
        regionalPlayout(ctx, NORTH, "2026-09-22T10:25:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie));
    }

    // ---------- 整次发布 422：任一区域阻断即整次拒绝 ----------

    @Test
    void publishBlockedByRevokedSpliceGrantWholeOrNothing() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)),
                region(SOUTH, window(ctx.spliceA, ctx.spliceGrantS, W1_FROM, W1_TO)));

        // 撤销 CN-NORTH 插播授权 → 整次发布 422，稳定列出区域与原因
        postJson("/api/grants/" + ctx.spliceGrantN + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.p + "-pub", 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_BLOCKED"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.violations[0].segmentId").value(ctx.segment))
                .andExpect(jsonPath("$.violations[0].reason").value("GRANT_INVALID"));

        // 不发布部分区域：仍无已发布编排
        regionalPlayout(ctx, SOUTH, AT_IN_W1)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 诊断接口同样列出阻断
        getJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/splice-diagnostics")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.violations[0].reason").value("GRANT_INVALID"));

        // 补发新授权后整次发布成功（发布版本仍为 1，证明此前未部分发布）
        long newGrant = createGrant(ctx.channel, ctx.spliceA, NORTH, GRANT_FROM, GRANT_TO);
        configureSplice(ctx, "sk-" + ctx.p + "-cfg2",
                region(NORTH, window(ctx.spliceA, newGrant, W1_FROM, W1_TO)),
                region(SOUTH, window(ctx.spliceA, ctx.spliceGrantS, W1_FROM, W1_TO)));
        publish(ctx, "req-" + ctx.p + "-pub2", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    @Test
    void publishBlockedByBlackoutAndAssetWithdrawal() throws Exception {
        // 场景一：黑屏窗口与插播窗口相交 → 422；取消黑屏后发布成功
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));
        MvcResult blackout = postJson("/api/channels/" + ctx.channel + "/blackouts",
                        Map.of("requestId", "req-" + ctx.p + "-bo", "regionCode", NORTH,
                                "start", W1_FROM, "end", W1_TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long blackoutId = readJson(blackout).get("id").asLong();

        publish(ctx, "req-" + ctx.p + "-pub", 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_BLOCKED"))
                .andExpect(jsonPath("$.violations[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.violations[0].reason").value("BLACKOUT_OVERLAP"));

        // 黑屏创建幂等：同 requestId 重放返回首次结果
        postJson("/api/channels/" + ctx.channel + "/blackouts",
                        Map.of("requestId", "req-" + ctx.p + "-bo", "regionCode", NORTH,
                                "start", W1_FROM, "end", W1_TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(blackoutId));

        // 取消黑屏（幂等）后发布成功
        postJson("/api/blackouts/" + blackoutId + "/cancel",
                Map.of("requestId", "req-" + ctx.p + "-bc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/blackouts/" + blackoutId + "/cancel",
                Map.of("requestId", "req-" + ctx.p + "-bc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        postJson("/api/blackouts/" + blackoutId + "/cancel",
                Map.of("requestId", "req-" + ctx.p + "-bc2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BLACKOUT_NOT_ACTIVE"));
        publish(ctx, "req-" + ctx.p + "-pub2", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 场景二：插播素材被撤回 → 422 ASSET_WITHDRAWN
        Ctx ctx2 = newContext();
        configureSplice(ctx2, "sk-" + ctx2.p + "-cfg",
                region(NORTH, window(ctx2.spliceA, ctx2.spliceGrantN, W1_FROM, W1_TO)));
        postJson("/api/assets/" + ctx2.spliceA + "/withdraw",
                Map.of("requestId", "req-" + ctx2.p + "-wd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true));
        publish(ctx2, "req-" + ctx2.p + "-pub", 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SPLICE_BLOCKED"))
                .andExpect(jsonPath("$.violations[0].reason").value("ASSET_WITHDRAWN"));
    }

    @Test
    void violationsListedStablyAcrossRegions() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)),
                region(SOUTH, window(ctx.spliceA, ctx.spliceGrantS, W1_FROM, W1_TO)));
        // 两个区域的插播授权均撤销 → 违规按区域码稳定排序列出
        postJson("/api/grants/" + ctx.spliceGrantS + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv-s")).andExpect(status().isOk());
        postJson("/api/grants/" + ctx.spliceGrantN + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv-n")).andExpect(status().isOk());
        publish(ctx, "req-" + ctx.p + "-pub", 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].regionCode").value(NORTH))
                .andExpect(jsonPath("$.violations[1].regionCode").value(SOUTH));
    }

    // ---------- 授权版本与 spliceKey 指纹 ----------

    @Test
    void grantVersionBumpChangesSpliceKeyFingerprint() throws Exception {
        Ctx ctx = newContext();
        Map<String, Object> body = spliceBody("sk-" + ctx.p + "-fp", 1,
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));
        putJson(spliceUrl(ctx), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].windows[0].grantVersion").value(1));

        // 撤销授权：授权版本递增
        postJson("/api/grants/" + ctx.spliceGrantN + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.revoked").value(true));

        // 同 spliceKey 重放：指纹含授权版本，版本已变 → 409 而非重放首次结果
        putJson(spliceUrl(ctx), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 新授权 + 新 spliceKey：配置成功，固化新授权版本
        long newGrant = createGrant(ctx.channel, ctx.spliceA, NORTH, GRANT_FROM, GRANT_TO);
        putJson(spliceUrl(ctx), spliceBody("sk-" + ctx.p + "-fp2", 1,
                        region(NORTH, window(ctx.spliceA, newGrant, W1_FROM, W1_TO))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0].windows[0].grantId").value((int) newGrant))
                .andExpect(jsonPath("$.regions[0].windows[0].grantVersion").value(1));
    }

    // ---------- 发布后授权撤销：快照不改写，区域决策回退主素材 ----------

    @Test
    void revokeAfterPublishKeepsSnapshotAndFallsBackToMainAsset() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));
        long publicationId = readPublicationId(publish(ctx, "req-" + ctx.p + "-pub", 0)
                .andExpect(status().isOk()).andReturn());

        // 发布后撤销插播授权：快照行不被改写
        postJson("/api/grants/" + ctx.spliceGrantN + "/revoke",
                Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());
        getJson("/api/publications/" + publicationId + "/snapshot")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].assetId").value(ctx.spliceA))
                .andExpect(jsonPath("$.regions[?(@.source=='SPLICE')].grantVersion").value(1));

        // 区域决策：窗口内回退主素材并给出原因，不回退到保底素材
        regionalPlayout(ctx, NORTH, AT_IN_W1)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));
        // 回执同样按快照解析为主素材
        createReceipt(ctx, NORTH, "req-" + ctx.p + "-rc", AT_IN_W1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ctx.movie))
                .andExpect(jsonPath("$.source").value("PROGRAM"));
    }

    // ---------- 素材撤回与版本拉取 ----------

    @Test
    void assetWithdrawAndPullIdempotency() throws Exception {
        Ctx ctx = newContext();

        // 撤回主流程：终态且版本递增
        postJson("/api/assets/" + ctx.spliceB + "/withdraw",
                Map.of("requestId", "req-" + ctx.p + "-wd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn").value(true))
                .andExpect(jsonPath("$.version").value(2));
        // 同 requestId 重放：返回首次结果
        postJson("/api/assets/" + ctx.spliceB + "/withdraw",
                Map.of("requestId", "req-" + ctx.p + "-wd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 新 requestId 重复撤回：409
        postJson("/api/assets/" + ctx.spliceB + "/withdraw",
                Map.of("requestId", "req-" + ctx.p + "-wd2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ASSET_ALREADY_WITHDRAWN"));
        // 404：素材不存在
        postJson("/api/assets/" + ctx.p + "ghost/withdraw",
                Map.of("requestId", "req-" + ctx.p + "-wd3"))
                .andExpect(status().isNotFound());

        // 版本拉取：版本递增且幂等
        postJson("/api/assets/" + ctx.movie + "/pull",
                Map.of("requestId", "req-" + ctx.p + "-pl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/assets/" + ctx.movie + "/pull",
                Map.of("requestId", "req-" + ctx.p + "-pl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/assets/" + ctx.movie + "/pull",
                Map.of("requestId", "req-" + ctx.p + "-pl2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        postJson("/api/assets/" + ctx.p + "ghost/pull",
                Map.of("requestId", "req-" + ctx.p + "-pl3"))
                .andExpect(status().isNotFound());
    }

    // ---------- 并发：同 spliceKey 幂等 / 发布与撤销、黑屏按提交顺序裁决 ----------

    @Test
    void concurrentSpliceConfigSameKeyIdempotent() throws Exception {
        Ctx ctx = newContext();
        String url = spliceUrl(ctx);
        Map<String, Object> body = spliceBody("sk-" + ctx.p + "-cc", 1,
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));

        List<Integer> statuses = runConcurrently(2, i ->
                mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                        .andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        // 仅一份配置生效
        getJson(url).andExpect(jsonPath("$.regions[0].windows.length()").value(1));
    }

    @Test
    void concurrentPublishAndSpliceGrantRevokeCommitOrder() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));

        // 并发：发布 vs 撤销插播授权，按事务提交顺序裁决
        List<Integer> statuses = runConcurrently(2, i -> {
            if (i == 0) {
                return mvc.perform(post("/api/channels/" + ctx.channel + "/drafts/" + DAY
                                        + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + ctx.p + "-pub",
                                        "draftVersion", 1, "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus();
            }
            return mvc.perform(post("/api/grants/" + ctx.spliceGrantN + "/revoke")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("requestId", "req-" + ctx.p + "-rv"))))
                    .andReturn().getResponse().getStatus();
        });
        int publishStatus = statuses.get(0);
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(publishStatus).isIn(200, 422);

        if (publishStatus == 422) {
            // 撤销先提交：整次发布拒绝，无部分发布
            regionalPlayout(ctx, NORTH, AT_IN_W1)
                    .andExpect(jsonPath("$.source").value("FALLBACK"))
                    .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        } else {
            // 发布先提交：快照已固化，撤销后窗口内回退主素材
            regionalPlayout(ctx, NORTH, AT_IN_W1)
                    .andExpect(jsonPath("$.source").value("PROGRAM"))
                    .andExpect(jsonPath("$.assetId").value(ctx.movie))
                    .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));
        }
    }

    @Test
    void concurrentBlackoutCreateAndPublishCommitOrder() throws Exception {
        Ctx ctx = newContext();
        configureSplice(ctx, "sk-" + ctx.p + "-cfg",
                region(NORTH, window(ctx.spliceA, ctx.spliceGrantN, W1_FROM, W1_TO)));

        List<Integer> statuses = runConcurrently(2, i -> {
            if (i == 0) {
                return mvc.perform(post("/api/channels/" + ctx.channel + "/drafts/" + DAY
                                        + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + ctx.p + "-pub",
                                        "draftVersion", 1, "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus();
            }
            return mvc.perform(post("/api/channels/" + ctx.channel + "/blackouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("requestId", "req-" + ctx.p + "-bo",
                                    "regionCode", NORTH, "start", W1_FROM, "end", W1_TO))))
                    .andReturn().getResponse().getStatus();
        });
        int publishStatus = statuses.get(0);
        assertThat(statuses.get(1)).isEqualTo(200);
        assertThat(publishStatus).isIn(200, 422);

        if (publishStatus == 422) {
            // 黑屏先提交：发布被阻断；取消黑屏后可重新发布成功
            MvcResult diagnostics = getJson(
                    "/api/channels/" + ctx.channel + "/drafts/" + DAY + "/splice-diagnostics")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.violations[0].reason").value("BLACKOUT_OVERLAP"))
                    .andReturn();
            assertThat(readJson(diagnostics).get("violations").size()).isEqualTo(1);
        } else {
            // 发布先提交：版本 1 已固化
            regionalPlayout(ctx, NORTH, AT_IN_W1)
                    .andExpect(jsonPath("$.source").value("SPLICE"))
                    .andExpect(jsonPath("$.assetId").value(ctx.spliceA));
        }
    }

    // ---------- 测试辅助 ----------

    private static final class Ctx {
        private String p;
        private String channel;
        private String fallback;
        private String movie;
        private String spliceA;
        private String spliceB;
        private String segment;
        private long spliceGrantN;
        private long spliceGrantS;
    }

    /** 创建频道、素材、主素材授权与两区域插播授权，并建 10:00-11:00 单条目草稿。 */
    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.p = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        ctx.channel = ctx.p + "ch";
        ctx.fallback = ctx.p + "fallback";
        ctx.movie = ctx.p + "movie";
        ctx.spliceA = ctx.p + "splice-a";
        ctx.spliceB = ctx.p + "splice-b";
        ctx.segment = ctx.p + "seg-1";

        postJson("/api/assets", Map.of("id", ctx.fallback, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movie, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.spliceA, "durationMs", 600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.spliceB, "durationMs", 600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallback))
                .andExpect(status().isOk());
        createGrant(ctx.channel, ctx.movie, null, GRANT_FROM, GRANT_TO);
        ctx.spliceGrantN = createGrant(ctx.channel, ctx.spliceA, NORTH, GRANT_FROM, GRANT_TO);
        ctx.spliceGrantS = createGrant(ctx.channel, ctx.spliceA, SOUTH, GRANT_FROM, GRANT_TO);

        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + ctx.p + "-d1", 0,
                                segment(ctx.segment, ctx.movie, T_10, T_11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        return ctx;
    }

    private long createGrant(String channel, String asset, String regionCode,
                             String from, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channel);
        body.put("assetId", asset);
        body.put("regionCode", regionCode);
        body.put("validFrom", from);
        body.put("validTo", to);
        MvcResult result = postJson("/api/grants", body)
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private void configureSplice(Ctx ctx, String spliceKey,
                                 Map<String, Object>... regions) throws Exception {
        putJson(spliceUrl(ctx), spliceBody(spliceKey, 1, regions))
                .andExpect(status().isOk());
    }

    private String spliceUrl(Ctx ctx) {
        return "/api/channels/" + ctx.channel + "/drafts/" + DAY
                + "/segments/" + ctx.segment + "/splices";
    }

    private ResultActions publish(Ctx ctx, String requestId, long expectedPublishedVersion)
            throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "draftVersion", 1,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions regionalPlayout(Ctx ctx, String regionCode, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/regions/" + regionCode
                + "/playout").param("at", at)).andExpect(status().isOk());
    }

    private ResultActions createReceipt(Ctx ctx, String regionCode, String requestId, String at)
            throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/regions/" + regionCode + "/receipts",
                Map.of("requestId", requestId, "at", at));
    }

    private long readPublicationId(MvcResult result) throws Exception {
        return readJson(result).get("publicationId").asLong();
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

    private static Map<String, Object> window(String assetId, long grantId,
                                              String start, String end) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("assetId", assetId);
        window.put("grantId", grantId);
        window.put("start", start);
        window.put("end", end);
        return window;
    }

    private static Map<String, Object> region(String regionCode, Map<String, Object>... windows) {
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("regionCode", regionCode);
        region.put("windows", List.of(windows));
        return region;
    }

    private static Map<String, Object> spliceBody(String spliceKey, long expectedDraftVersion,
                                                  Map<String, Object>... regions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spliceKey", spliceKey);
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.put("regions", List.of(regions));
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
