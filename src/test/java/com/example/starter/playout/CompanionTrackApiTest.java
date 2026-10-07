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
 * 伴随轨（companion tracks）端到端测试：主素材 + 最多 2 条有序伴随的完整性组合。
 * 覆盖主流程、授权失效整体回退、失败分支原子性、幂等与并发边界；
 * 运行环境为 H2（MODE=MySQL）内存库，快照冻结与事务回滚由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanionTrackApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：M + S1/S2 发布，播放返回主素材与有序伴随 ----------

    @Test
    void publishWithCompanionsAndPlayoutReturnsOrderedSet() throws Exception {
        Ctx ctx = newContext(true);

        // 草稿响应含有序伴随
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].companions[0].assetId").value(ctx.s1))
                .andExpect(jsonPath("$.segments[0].companions[0].grantId").value(ctx.s1Grant))
                .andExpect(jsonPath("$.segments[0].companions[1].assetId").value(ctx.s2))
                .andExpect(jsonPath("$.segments[0].companions[1].grantId").value(ctx.s2Grant));

        publish(ctx, "req-" + ctx.p + "-p1", 1, 0).andExpect(status().isOk());

        // 播放命中：主素材 M + 有序伴随 [S1, S2]
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie))
                .andExpect(jsonPath("$.companions[0].assetId").value(ctx.s1))
                .andExpect(jsonPath("$.companions[0].grantId").value(ctx.s1Grant))
                .andExpect(jsonPath("$.companions[1].assetId").value(ctx.s2))
                .andExpect(jsonPath("$.companions[1].grantId").value(ctx.s2Grant))
                .andExpect(jsonPath("$.companions.length()").value(2));
    }

    // ---------- 任一伴随授权失效：整片段回退保底、伴随为空、旧快照仍冻结 ----------

    @Test
    void revokeOneCompanionFallsBackAndSnapshotFrozen() throws Exception {
        Ctx ctx = newContext(true);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk());
        MvcResult published = publish(ctx, "req-" + ctx.p + "-p1", 1, 0)
                .andExpect(status().isOk()).andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 只撤 S1 的授权
        postJson("/api/grants/" + ctx.s1Grant + "/revoke", Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());

        // 整个片段回退保底，伴随集合为空，仍保留旧发布快照引用
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallback))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.segmentId").value(ctx.p + "seg-1"))
                .andExpect(jsonPath("$.companions.length()").value(0));

        // 旧发布快照仍列 M、S1、S2 与原授权（冻结，不按当前状态重算）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT position, asset_id, grant_id FROM playout_publication_segment_companion"
                        + " WHERE publication_id = ? ORDER BY position", publicationId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("asset_id")).isEqualTo(ctx.s1);
        assertThat(rows.get(0).get("grant_id")).isEqualTo(ctx.s1Grant);
        assertThat(rows.get(1).get("asset_id")).isEqualTo(ctx.s2);
        assertThat(rows.get(1).get("grant_id")).isEqualTo(ctx.s2Grant);
        Map<String, Object> main = jdbc.queryForMap(
                "SELECT asset_id, grant_id FROM playout_publication_segment"
                        + " WHERE publication_id = ? AND segment_id = ?",
                publicationId, ctx.p + "seg-1");
        assertThat(main.get("asset_id")).isEqualTo(ctx.movie);
    }

    // ---------- 伴随结构约束：数量上限与同素材重复 400 ----------

    @Test
    void companionStructureValidation() throws Exception {
        Ctx ctx = newContext(true);

        // 400：超过 2 条伴随
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-many", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, ctx.s1Grant),
                                        companion(ctx.s2, ctx.s2Grant),
                                        companion(ctx.s1, ctx.s1Grant)))))
                .andExpect(status().isBadRequest());

        // 400：伴随同素材重复
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-dup", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, ctx.s1Grant),
                                        companion(ctx.s1, ctx.s1Grant)))))
                .andExpect(status().isBadRequest());

        // 400：伴随素材与主素材相同
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-same", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.movie, ctx.movieGrant)))))
                .andExpect(status().isBadRequest());

        // 失败不写一半：草稿仍不存在
        getJson("/api/channels/" + ctx.channel + "/playout", T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
    }

    // ---------- 伴随授权缺失：404 / 422，整份草稿不写一半 ----------

    @Test
    void companionGrantValidationFailuresAreAtomic() throws Exception {
        Ctx ctx = newContext(true);

        // 404：伴随指定授权不存在
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-g404", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, 999999999L)))))
                .andExpect(status().isNotFound());

        // 422：授权素材不符（S1 的授权指给 S2）
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-gmm", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s2, ctx.s1Grant)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));

        // 422：授权频道不符（其他频道的授权）
        String otherChannel = ctx.p + "ch-other";
        postJson("/api/assets", Map.of("id", ctx.p + "fb2", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", otherChannel, "fallbackAssetId", ctx.p + "fb2"))
                .andExpect(status().isOk());
        long foreignGrant = createGrant(otherChannel, ctx.s1);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-gch", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, foreignGrant)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));

        // 422：授权未完整覆盖片段区间
        long narrowGrant = createGrant(ctx.channel, ctx.s1,
                "2026-09-22T10:00:00.001+08:00", GRANT_TO);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-gcov", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, narrowGrant)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"));

        // 422：授权已撤销
        long revokedGrant = createGrant(ctx.channel, ctx.s1);
        postJson("/api/grants/" + revokedGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.p + "-rv0"))
                .andExpect(status().isOk());
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-grv", 0, segment(ctx.p + "seg-1", ctx.movie,
                                List.of(companion(ctx.s1, revokedGrant)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 全部失败：草稿未写入，版本仍为首次创建
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-ok", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 发布时伴随授权已失效：422 整次拒绝，不产生快照 ----------

    @Test
    void publishFailsWhenCompanionGrantRevokedAfterDraft() throws Exception {
        Ctx ctx = newContext(true);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk());

        postJson("/api/grants/" + ctx.s2Grant + "/revoke",
                        Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());

        publish(ctx, "req-" + ctx.p + "-p1", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 无快照产生：仍无已发布编排
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_publication WHERE channel_id = ?",
                Long.class, ctx.channel)).isZero();
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
    }

    // ---------- 主授权失效同样回退且伴随为空 ----------

    @Test
    void revokeMainGrantFallsBackWithEmptyCompanions() throws Exception {
        Ctx ctx = newContext(true);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.p + "-p1", 1, 0).andExpect(status().isOk());

        postJson("/api/grants/" + ctx.movieGrant + "/revoke",
                        Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());

        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallback))
                .andExpect(jsonPath("$.companions.length()").value(0));
    }

    // ---------- 幂等与操作者：同参回放、异参 409、operator 落库 ----------

    @Test
    void idempotencyAndOperator() throws Exception {
        Ctx ctx = newContext(true);
        Map<String, Object> body = draftBody("req-" + ctx.p + "-idem", 0,
                ctx.segmentWithCompanions("seg-1"));
        body.put("operator", "op-zhang");

        // 首次成功，操作者落库
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT operator FROM playout_request WHERE request_id = ?",
                String.class, "req-" + ctx.p + "-idem")).isEqualTo("op-zhang");

        // 同 requestId 同参数（含伴随与 operator）重试：返回首次完整响应
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].companions.length()").value(2));

        // 同 requestId 异参（改伴随顺序）：409
        Map<String, Object> changed = draftBody("req-" + ctx.p + "-idem", 0,
                segment(ctx.p + "seg-1", ctx.movie, List.of(
                        companion(ctx.s2, ctx.s2Grant), companion(ctx.s1, ctx.s1Grant))));
        changed.put("operator", "op-zhang");
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, changed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 同 requestId 仅 operator 不同：409（操作者属于结构化身份）
        Map<String, Object> otherOp = draftBody("req-" + ctx.p + "-idem", 0,
                ctx.segmentWithCompanions("seg-1"));
        otherOp.put("operator", "op-li");
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, otherOp)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 400：operator 提供但为空白
        Map<String, Object> blankOp = draftBody("req-" + ctx.p + "-blank", 0,
                ctx.segmentWithCompanions("seg-1"));
        blankOp.put("operator", "   ");
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY, blankOp)
                .andExpect(status().isBadRequest());

        // 失败不占键：同 requestId 修正后成功
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-blank", 1, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 紧急插播命中时无伴随，结束后恢复当前授权判定 ----------

    @Test
    void emergencyOverridePlaysWithoutCompanionsThenResumes() throws Exception {
        Ctx ctx = newContext(true);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk());
        publish(ctx, "req-" + ctx.p + "-p1", 1, 0).andExpect(status().isOk());

        // 插播 10:20-10:40 命中：只播放插播素材、无日片段伴随
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + ctx.p + "-ov",
                        "overrideKey", ctx.p + "ov-1",
                        "channelId", ctx.channel,
                        "assetId", ctx.s1,
                        "grantId", ctx.s1Grant,
                        "priority", 5,
                        "start", "2026-09-22T10:20:00.000+08:00",
                        "end", "2026-09-22T10:40:00.000+08:00"))
                .andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.s1))
                .andExpect(jsonPath("$.companions.length()").value(0));

        // 插播结束后：按当前全部授权重新选日片段（含伴随）
        playout(ctx, "2026-09-22T10:45:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie))
                .andExpect(jsonPath("$.companions.length()").value(2));

        // 伴随授权撤销后：插播外回退保底
        postJson("/api/grants/" + ctx.s1Grant + "/revoke",
                        Map.of("requestId", "req-" + ctx.p + "-rv"))
                .andExpect(status().isOk());
        playout(ctx, "2026-09-22T10:45:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.companions.length()").value(0));
    }

    // ---------- 并发：发布与伴随授权撤销竞争，按提交顺序生效且状态一致 ----------

    @Test
    void concurrentPublishAndCompanionRevoke() throws Exception {
        Ctx ctx = newContext(true);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0, ctx.segmentWithCompanions("seg-1")))
                .andExpect(status().isOk());

        List<Integer> results = runConcurrently(List.of(
                () -> publish(ctx, "req-" + ctx.p + "-p1", 1, 0)
                        .andReturn().getResponse().getStatus(),
                () -> postJson("/api/grants/" + ctx.s1Grant + "/revoke",
                                Map.of("requestId", "req-" + ctx.p + "-rv"))
                        .andReturn().getResponse().getStatus()));

        int publishStatus = results.get(0);
        int revokeStatus = results.get(1);
        // 撤销恒成功；发布按提交顺序要么成功要么 422
        assertThat(revokeStatus).isEqualTo(200);
        assertThat(publishStatus).isIn(200, 422);

        Long publications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_publication WHERE channel_id = ?",
                Long.class, ctx.channel);
        if (publishStatus == 200) {
            // 发布先提交：快照存在，但播放按当前授权状态回退保底
            assertThat(publications).isEqualTo(1L);
            playout(ctx, T_1030)
                    .andExpect(jsonPath("$.source").value("FALLBACK"))
                    .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                    .andExpect(jsonPath("$.companions.length()").value(0));
        } else {
            // 撤销先提交：发布 422 且不落任何快照
            assertThat(publications).isZero();
            playout(ctx, T_1030)
                    .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        }
    }

    // ---------- 无伴随片段保持原契约 ----------

    @Test
    void plainSegmentKeepsOriginalContract() throws Exception {
        Ctx ctx = newContext(false);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.p + "-d1", 0,
                                segment(ctx.p + "seg-1", ctx.movie, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].companions.length()").value(0));
        publish(ctx, "req-" + ctx.p + "-p1", 1, 0).andExpect(status().isOk());
        playout(ctx, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movie))
                .andExpect(jsonPath("$.companions.length()").value(0));
    }

    // ---------- 测试辅助 ----------

    /** 测试上下文：频道、保底/主/伴随素材与各自授权，全部授权覆盖完整业务日。 */
    private record Ctx(String p, String channel, String fallback,
                       String movie, long movieGrant,
                       String s1, long s1Grant, String s2, long s2Grant) {
        Map<String, Object> segmentWithCompanions(String segmentId) {
            return segment(p + segmentId, movie, List.of(
                    companion(s1, s1Grant), companion(s2, s2Grant)));
        }
    }

    private Ctx newContext(boolean withCompanionGrants) throws Exception {
        String p = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "s1", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "s2", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        long movieGrant = createGrant(p + "ch", p + "movie");
        long s1Grant = withCompanionGrants ? createGrant(p + "ch", p + "s1") : 0L;
        long s2Grant = withCompanionGrants ? createGrant(p + "ch", p + "s2") : 0L;
        return new Ctx(p, p + "ch", p + "fallback", p + "movie", movieGrant,
                p + "s1", s1Grant, p + "s2", s2Grant);
    }

    private long createGrant(String channel, String asset) throws Exception {
        return createGrant(channel, asset, GRANT_FROM, GRANT_TO);
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> companion(String assetId, long grantId) {
        Map<String, Object> companion = new LinkedHashMap<>();
        companion.put("assetId", assetId);
        companion.put("grantId", grantId);
        return companion;
    }

    private static Map<String, Object> segment(String id, String assetId,
                                               List<Map<String, Object>> companions) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", T_10);
        segment.put("end", T_11);
        segment.put("companions", companions);
        return segment;
    }

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 Map<String, Object> segment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segment));
        return body;
    }

    private ResultActions publish(Ctx ctx, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions playout(Ctx ctx, String at) throws Exception {
        return getJson("/api/channels/" + ctx.channel + "/playout", at);
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

    private interface ThrowingCall {
        Integer call() throws Exception;
    }

    /** 并发执行两组调用，就绪后同时放行；验证真实事务竞争而非串行重放。 */
    private static List<Integer> runConcurrently(List<ThrowingCall> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (ThrowingCall call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return call.call();
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


