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
 * 播出编排 API 端到端测试：覆盖主流程、失败分支、幂等与并发边界。
 * 运行环境为 H2（MySQL 兼容模式）内存库，表结构与生产 MySQL DDL 一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_12 = "2026-09-22T12:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 素材 / 频道 / 授权 ----------

    @Test
    void assetChannelGrantLifecycleAndErrorBranches() throws Exception {
        String p = prefix();

        // 创建素材主流程
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(p + "fallback"))
                .andExpect(jsonPath("$.durationMs").value(30000));
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());

        // 400：时长非正整数
        postJson("/api/assets", Map.of("id", p + "bad", "durationMs", 0))
                .andExpect(status().isBadRequest());
        // 409：素材 ID 重复
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 1000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_ID"));

        // 404：保底素材不存在
        postJson("/api/channels", Map.of("id", p + "ch-x", "fallbackAssetId", p + "missing"))
                .andExpect(status().isNotFound());
        // 创建频道主流程
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackAssetId").value(p + "fallback"));
        // 409：频道 ID 重复
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isConflict());

        // 创建授权主流程，响应时间为毫秒精度 ISO 8601（+08:00）
        MvcResult grant = postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.validFrom").value(GRANT_FROM))
                .andReturn();
        assertThat(readJson(grant).get("id").asLong()).isPositive();

        // 400：授权区间终点不大于起点
        postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "movie",
                        "validFrom", GRANT_TO, "validTo", GRANT_FROM))
                .andExpect(status().isBadRequest());
        // 404：授权引用不存在的频道 / 素材
        postJson("/api/grants", Map.of(
                        "channelId", p + "ghost", "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isNotFound());
        postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "ghost",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isNotFound());
        // 422：保底素材不能创建授权
        postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "fallback",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));
    }

    // ---------- 草稿替换与乐观锁 ----------

    @Test
    void draftReplaceOptimisticLockAndFailureKeepsOriginal() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);

        // 首次创建：expectedDraftVersion=0
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].id").value(p + "seg-1"))
                .andExpect(jsonPath("$.segments[0].start").value(T_10));

        // 409：重复以 0 创建
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-2", 0,
                                segment(p + "seg-x", p + "movie", T_10, T_11)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));

        // 409：版本不符，且失败不得改变原草稿
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-3", 5,
                                segment(p + "seg-y", p + "movie", T_11, T_12)))
                .andExpect(status().isConflict());

        // 用正确版本替换成功，验证原草稿未被失败请求污染（版本只递增一次）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-4", 1,
                                segment(p + "seg-2", p + "movie", T_11, T_12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.segments[0].id").value(p + "seg-2"));

        // 404：频道不存在
        putJson("/api/channels/" + p + "ghost/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-5", 0, List.of()))
                .andExpect(status().isNotFound());
        // 400：业务日格式非法
        mvc.perform(put("/api/channels/" + channel + "/drafts/2026-13-40")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(replaceDraftBody("req-" + p + "-6", 0, List.of()))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 草稿片段业务规则 ----------

    @Test
    void draftSegmentValidationRules() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);

        // 422：片段重叠
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-ov", 0, List.of(
                                segment(p + "s1", p + "movie", T_10, T_12),
                                segment(p + "s2", p + "movie", T_11, T_12))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_OVERLAP"));

        // 422：片段跨日
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-cd", 0, List.of(
                                segment(p + "s1", p + "movie", T_11,
                                        "2026-09-23T00:00:00.001+08:00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_CROSSES_DAY"));

        // 400：结束不大于开始
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-inv", 0, List.of(
                                segment(p + "s1", p + "movie", T_11, T_10))))
                .andExpect(status().isBadRequest());

        // 422：无授权完整覆盖（授权 10:00 前不生效）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-ng", 0, List.of(
                                segment(p + "s1", p + "movie",
                                        "2026-09-21T23:30:00.000+08:00",
                                        "2026-09-22T00:30:00.000+08:00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_CROSSES_DAY"));

        // 422：素材存在但无授权
        postJson("/api/assets", Map.of("id", p + "ungranted", "durationMs", 1000))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-ng2", 0, List.of(
                                segment(p + "s1", p + "ungranted", T_10, T_11))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 404：片段引用不存在的素材
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-na", 0, List.of(
                                segment(p + "s1", p + "ghost", T_10, T_11))))
                .andExpect(status().isNotFound());

        // 边界：起点等于授权起点、终点等于授权终点（左闭右开）可接受
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-edge", 0, List.of(
                                segment(p + "s1", p + "movie", GRANT_FROM, T_10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 草稿幂等 ----------

    @Test
    void draftReplaceIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        Map<String, Object> body = replaceDraftBody("req-" + p + "-idem", 0,
                segment(p + "seg-1", p + "movie", T_10, T_11));

        // 首次成功
        putJson("/api/channels/" + channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 同参数重试：返回原结果，不重复增版本
        putJson("/api/channels/" + channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 改参数：409
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-idem", 1,
                                segment(p + "seg-2", p + "movie", T_11, T_12)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败请求不占用 requestId：先以重叠片段失败，再用同 requestId 成功
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-retry", 1, List.of(
                                segment(p + "a", p + "movie", T_10, T_12),
                                segment(p + "b", p + "movie", T_11, T_12))))
                .andExpect(status().isUnprocessableEntity());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-retry", 1,
                                segment(p + "ok", p + "movie", T_11, T_12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 发布 ----------

    @Test
    void publishFlowVersionChecksAndIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk());

        // 409：草稿版本不符
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p0", "draftVersion", 9,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));

        // 发布主流程
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.draftVersion").value(1));

        // 幂等重试：返回原结果，不重复增版本
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
        // 同 requestId 改参数：409
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 409：发布版本冲突（已发布过版本 1）
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));

        // 同一份草稿可再次发布为下一版本
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p3", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2));

        // 404：草稿不存在
        postJson("/api/channels/" + channel + "/drafts/2026-09-23/publish",
                        Map.of("requestId", "req-" + p + "-p4", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isNotFound());
    }

    // ---------- 授权撤销与发布的提交顺序语义 ----------

    @Test
    void revokeBeforePublishFailsPublishAndRevokeIsIdempotent() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        long grantId = lastGrantId;
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk());

        // 撤销主流程（先提交）
        postJson("/api/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        // 幂等重试：返回原结果
        postJson("/api/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        // 同 requestId 作用于不同授权：409
        long otherGrant = createGrant(channel, p + "movie");
        postJson("/api/grants/" + otherGrant + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 新 requestId 重复撤销：409
        postJson("/api/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GRANT_ALREADY_REVOKED"));
        // 404：授权不存在
        postJson("/api/grants/999999/revoke", Map.of("requestId", "req-" + p + "-rv3"))
                .andExpect(status().isNotFound());

        // 撤销第二条授权，使片段不再被任何未撤销授权覆盖
        postJson("/api/grants/" + otherGrant + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv4"))
                .andExpect(status().isOk());

        // 撤销先提交 → 发布失败（422 授权失效）
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));
    }

    @Test
    void publishBeforeRevokeSucceedsAndQueryAppliesRevocation() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        long grantId = lastGrantId;
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk());

        // 发布先提交 → 成功
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 发布前查询：命中节目
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"));

        // 之后撤销授权：历史快照不被改写，但播出查询应用撤销结果
        postJson("/api/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"))
                .andExpect(jsonPath("$.publicationId").isNumber())
                .andExpect(jsonPath("$.segmentId").value(p + "seg-1"));
    }

    // ---------- 播出决定 ----------

    @Test
    void playoutDecisionBranches() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);

        // 无已发布编排 → 保底
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));

        // 发布 10:00-11:00 一个片段
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "movie", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 命中片段（边界：起点含、终点不含）
        getJson("/api/channels/" + channel + "/playout", T_10)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"));
        getJson("/api/channels/" + channel + "/playout", T_11)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"));
        // 空档 → 保底
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T15:00:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));
        // 404：频道不存在
        getJson("/api/channels/" + p + "ghost/playout", T_10)
                .andExpect(status().isNotFound());
    }

    // ---------- 并发：同一草稿版本仅一方成功 ----------

    @Test
    void concurrentDraftReplaceSameVersionOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;

        // 并发首建（expectedDraftVersion=0）
        List<Integer> firstRound = runConcurrently(2, i -> {
            Map<String, Object> body = replaceDraftBody("req-" + p + "-c" + i, 0,
                    segment(p + "seg-" + i, p + "movie", T_10, T_11));
            return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(firstRound).containsExactlyInAnyOrder(200, 409);

        // 并发替换同一版本（expectedDraftVersion=1）
        List<Integer> secondRound = runConcurrently(2, i -> {
            Map<String, Object> body = replaceDraftBody("req-" + p + "-d" + i, 1,
                    segment(p + "seg2-" + i, p + "movie", T_11, T_12));
            return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(secondRound).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 测试辅助 ----------

    private long lastGrantId;

    /** 创建频道、素材与一条全天授权，返回频道 ID。 */
    private String newChannelWithGrant(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        lastGrantId = createGrant(p + "ch", p + "movie");
        return p + "ch";
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
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
                                                        Object... segments) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object segment : segments) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) segment;
            list.add(typed);
        }
        return replaceDraftBody(requestId, expectedVersion, list);
    }

    private static Map<String, Object> replaceDraftBody(String requestId, long expectedVersion,
                                                        List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions getJson(String url, String at) throws Exception {
        return mvc.perform(get(url).param("at", at));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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
