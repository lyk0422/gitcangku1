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
 * 伴随轨端到端测试：主素材+有序伴随的草稿、发布、播放与回退，覆盖失败分支、幂等与并发。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanionTrackApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_1045 = "2026-09-22T10:45:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：主素材 + 有序伴随的草稿、发布与播放 ----------

    @Test
    void companionDraftPublishAndPlayoutMainFlow() throws Exception {
        Ctx ctx = newContext();

        // 草稿：主 movie + 伴随 song、spot（各指定授权，与主片段同起止）
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant),
                                        companion(ctx.spotAsset, ctx.spotGrant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].companions[0].assetId").value(ctx.songAsset))
                .andExpect(jsonPath("$.segments[0].companions[0].grantId").value(ctx.songGrant))
                .andExpect(jsonPath("$.segments[0].companions[1].assetId").value(ctx.spotAsset));

        // 顺序有意义：倒序替换后响应顺序随之变化
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d2", 1,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.spotAsset, ctx.spotGrant),
                                        companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].companions[0].assetId").value(ctx.spotAsset))
                .andExpect(jsonPath("$.segments[0].companions[1].assetId").value(ctx.songAsset));

        publish(ctx.channel, "req-" + ctx.prefix + "p1", 2, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 播放命中：返回主素材与有序伴随集合（与草稿最终顺序一致）
        playout(ctx.channel, T_1030)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.companions.length()").value(2))
                .andExpect(jsonPath("$.companions[0].assetId").value(ctx.spotAsset))
                .andExpect(jsonPath("$.companions[0].grantId").value(ctx.spotGrant))
                .andExpect(jsonPath("$.companions[1].assetId").value(ctx.songAsset))
                .andExpect(jsonPath("$.companions[1].grantId").value(ctx.songGrant));
    }

    // ---------- 普通无伴随片段保持原契约 ----------

    @Test
    void plainSegmentKeepsOriginalContract() throws Exception {
        Ctx ctx = newContext();
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].companions").isEmpty());
        publish(ctx.channel, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.companions").isEmpty());
        // 空档保底：伴随集合为空
        playout(ctx.channel, "2026-09-22T15:00:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.companions").isEmpty());
    }

    // ---------- 400：同素材重复、超过 2 条、缺操作者、缺授权 ----------

    @Test
    void companionSyntaxValidationFailures() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        // 400：同素材伴随重复
        putJson(url, draftBody("req-" + ctx.prefix + "dup", 0,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.songGrant),
                                companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("DUPLICATE_COMPANION_ASSET"));

        // 400：伴随超过 2 条
        putJson(url, draftBody("req-" + ctx.prefix + "lim", 0,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.songGrant),
                                companion(ctx.spotAsset, ctx.spotGrant),
                                companion(ctx.newsAsset, ctx.newsGrant))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("COMPANION_LIMIT_EXCEEDED"));

        // 400：伴随缺少授权
        Map<String, Object> noGrant = segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11);
        noGrant.put("companions", List.of(Map.of("assetId", ctx.songAsset)));
        putJson(url, draftBody("req-" + ctx.prefix + "ng", 0, noGrant))
                .andExpect(status().isBadRequest());

        // 400：草稿缺少操作者
        Map<String, Object> noOperator = draftBody("req-" + ctx.prefix + "no-op", 0,
                segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11));
        noOperator.remove("operator");
        putJson(url, noOperator).andExpect(status().isBadRequest());

        // 400：发布缺少操作者
        putJson(url, draftBody("req-" + ctx.prefix + "ok", 0,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-" + ctx.prefix + "p-no-op",
                                "draftVersion", 1, "expectedPublishedVersion", 0))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 404 / 422：伴随引用与授权规则，失败整份草稿不写一半 ----------

    @Test
    void companionReferenceAndGrantFailuresKeepDraftAtomic() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        // 先建立一份无伴随的合法草稿（版本 1）
        putJson(url, draftBody("req-" + ctx.prefix + "base", 0,
                        segment(ctx.prefix + "base-seg", ctx.movieAsset, T_10, T_11)))
                .andExpect(status().isOk());

        // 404：伴随素材不存在
        putJson(url, draftBody("req-" + ctx.prefix + "na", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.prefix + "ghost", ctx.songGrant))))
                .andExpect(status().isNotFound());
        // 404：伴随授权不存在
        putJson(url, draftBody("req-" + ctx.prefix + "ng", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, 999999L))))
                .andExpect(status().isNotFound());
        // 422：授权素材不匹配（movie 的授权用于 song 伴随）
        putJson(url, draftBody("req-" + ctx.prefix + "nm", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.movieGrant))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_MATCHED"));
        // 422：授权未完整覆盖区间
        long shortGrant = createGrant(ctx.channel, ctx.songAsset,
                "2026-09-22T10:00:00.000+08:00", "2026-09-22T10:20:00.000+08:00");
        putJson(url, draftBody("req-" + ctx.prefix + "nc", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, shortGrant))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"));
        // 422：伴随授权已撤销
        long revokedGrant = createGrant(ctx.channel, ctx.spotAsset, GRANT_FROM, GRANT_TO);
        revokeGrant(revokedGrant, "req-" + ctx.prefix + "rv0");
        putJson(url, draftBody("req-" + ctx.prefix + "ri", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.spotAsset, revokedGrant))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 上述失败均不写一半：草稿仍是版本 1 的原内容，可基于版本 1 成功替换
        putJson(url, draftBody("req-" + ctx.prefix + "after", 1,
                        segment(ctx.prefix + "s2", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.segments[0].id").value(ctx.prefix + "s2"))
                .andExpect(jsonPath("$.segments[0].companions.length()").value(1));
    }

    // ---------- 发布原子性：伴随授权撤销后发布 422，不产生半个发布 ----------

    @Test
    void publishFailsWhenCompanionGrantRevokedAndFailureNotConsuming() throws Exception {
        Ctx ctx = newContext();
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk());

        // 撤销先提交 → 发布 422
        revokeGrant(ctx.songGrant, "req-" + ctx.prefix + "rv");
        publish(ctx.channel, "req-" + ctx.prefix + "p1", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 不产生半个发布：库内无发布版本，播放仍无已发布编排
        Long versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_publication WHERE channel_id = ?",
                Long.class, ctx.channel);
        assertThat(versions).isZero();
        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.companions").isEmpty());

        // 失败不占 requestId：修复草稿（新授权）后用原 requestId 成功发布
        long newSongGrant = createGrant(ctx.channel, ctx.songAsset, GRANT_FROM, GRANT_TO);
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d2", 1,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, newSongGrant))))
                .andExpect(status().isOk());
        publish(ctx.channel, "req-" + ctx.prefix + "p1", 2, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    // ---------- 撤销伴随授权：整片段回退保底、伴随为空、旧快照冻结不重算 ----------

    @Test
    void revokeCompanionGrantFallsBackAndSnapshotFrozen() throws Exception {
        Ctx ctx = newContext();
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant),
                                        companion(ctx.spotAsset, ctx.spotGrant))))
                .andExpect(status().isOk());
        MvcResult published = publish(ctx.channel, "req-" + ctx.prefix + "p1", 1, 0)
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.companions.length()").value(2));

        // 只撤伴随 S 的授权：整个片段回退保底，伴随集合为空，仍保留旧发布快照引用
        revokeGrant(ctx.songGrant, "req-" + ctx.prefix + "rv-song");
        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(ctx.fallbackAsset))
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.segmentId").value(ctx.prefix + "seg-1"))
                .andExpect(jsonPath("$.companions").isEmpty());

        // 旧发布仍列 M、S 与原授权：快照行不被撤销改写
        List<Map<String, Object>> snapshot = jdbc.queryForList(
                "SELECT asset_id, grant_id, seq_no FROM playout_publication_segment_companion"
                        + " WHERE publication_id = ? ORDER BY seq_no", publicationId);
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).get("asset_id")).isEqualTo(ctx.songAsset);
        assertThat(snapshot.get(0).get("grant_id")).isEqualTo(ctx.songGrant);
        assertThat(snapshot.get(1).get("asset_id")).isEqualTo(ctx.spotAsset);
        assertThat(snapshot.get(1).get("grant_id")).isEqualTo(ctx.spotGrant);

        // 冻结语义：为同一伴随素材新建有效授权，旧发布不按当前状态重算，仍回退保底
        createGrant(ctx.channel, ctx.songAsset, GRANT_FROM, GRANT_TO);
        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.companions").isEmpty());
    }

    // ---------- 紧急插播共同判定：命中无伴随，结束回到日片段伴随 ----------

    @Test
    void emergencyOverridePlaysWithoutCompanionsThenBackToProgram() throws Exception {
        Ctx ctx = newContext();
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk());
        publish(ctx.channel, "req-" + ctx.prefix + "p1", 1, 0).andExpect(status().isOk());

        // 插播命中：只播放原插播素材，无日片段伴随
        postJson("/api/emergency-overrides", overrideBody("req-" + ctx.prefix + "ov",
                        ctx.prefix + "ov-1", ctx.channel, ctx.newsAsset, ctx.newsGrant,
                        5, T_1020, T_1040))
                .andExpect(status().isOk());
        playout(ctx.channel, T_1030)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(ctx.newsAsset))
                .andExpect(jsonPath("$.overrideKey").value(ctx.prefix + "ov-1"))
                .andExpect(jsonPath("$.companions").isEmpty());

        // 插播结束：重新按当前全部授权选日片段，伴随恢复
        playout(ctx.channel, T_1045)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.companions.length()").value(1))
                .andExpect(jsonPath("$.companions[0].assetId").value(ctx.songAsset));

        // 伴随授权撤销后，插播结束只能落到保底
        revokeGrant(ctx.songGrant, "req-" + ctx.prefix + "rv-song");
        playout(ctx.channel, T_1045)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.companions").isEmpty());
    }

    // ---------- 幂等：同参回首次完整响应、异参 409、失败不占键 ----------

    @Test
    void draftIdempotencyWithCompanions() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;
        Map<String, Object> body = draftBody("req-" + ctx.prefix + "idem", 0,
                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                        companion(ctx.songAsset, ctx.songGrant),
                        companion(ctx.spotAsset, ctx.spotGrant)));

        // 首次成功；同 requestId 同参数重放返回首次完整响应（含伴随），版本不重复递增
        String first = putJson(url, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String replay = putJson(url, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        // 同 requestId 异参（伴随顺序不同即不同参数）：409
        putJson(url, draftBody("req-" + ctx.prefix + "idem", 0,
                        segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.spotAsset, ctx.spotGrant),
                                companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以重复伴随素材 400，再用同 requestId 成功
        putJson(url, draftBody("req-" + ctx.prefix + "retry", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.songGrant),
                                companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isBadRequest());
        putJson(url, draftBody("req-" + ctx.prefix + "retry", 1,
                        segment(ctx.prefix + "s1", ctx.movieAsset, T_10, T_11,
                                companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 并发：发布与伴随授权撤销按提交顺序生效 ----------

    @Test
    void concurrentPublishAndCompanionRevokeFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        putJson("/api/channels/" + ctx.channel + "/drafts/" + DAY,
                        draftBody("req-" + ctx.prefix + "d1", 0,
                                segment(ctx.prefix + "seg-1", ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant))))
                .andExpect(status().isOk());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publishFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return publish(ctx.channel, "req-" + ctx.prefix + "p1", 1, 0)
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return revokeGrant(ctx.songGrant, "req-" + ctx.prefix + "rv")
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            // 撤销本身成功；发布要么 200（发布先提交），要么 422（撤销先提交）
            assertThat(revokeStatus).isEqualTo(200);
            assertThat(publishStatus).isIn(200, 422);

            MvcResult decision = playout(ctx.channel, T_1030).andReturn();
            JsonNode json = readJson(decision);
            if (publishStatus == 200) {
                // 发布先提交：快照已冻结，但伴随授权当前失效 → 整片段回退保底、伴随为空
                assertThat(json.get("source").asText()).isEqualTo("FALLBACK");
                assertThat(json.get("reason").asText()).isEqualTo("GRANT_REVOKED");
                assertThat(json.get("assetId").asText()).isEqualTo(ctx.fallbackAsset);
                assertThat(json.get("companions")).isEmpty();
            } else {
                // 撤销先提交：发布被拒绝，无已发布编排
                assertThat(json.get("source").asText()).isEqualTo("FALLBACK");
                assertThat(json.get("reason").asText()).isEqualTo("NO_PUBLISHED_SCHEDULE");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 并发：同版本草稿替换仅一方成功，伴随轨不残留半份 ----------

    @Test
    void concurrentDraftReplaceWithCompanionsOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        String url = "/api/channels/" + ctx.channel + "/drafts/" + DAY;

        List<Integer> statuses = runConcurrently(2, i -> putJson(url,
                        draftBody("req-" + ctx.prefix + "c" + i, 0,
                                segment(ctx.prefix + "seg-" + i, ctx.movieAsset, T_10, T_11,
                                        companion(ctx.songAsset, ctx.songGrant),
                                        companion(ctx.spotAsset, ctx.spotGrant))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终库内状态：恰好 1 个片段、2 条伴随，且伴随都属于幸存片段（不写一半）
        List<Map<String, Object>> segments = jdbc.queryForList(
                "SELECT id FROM playout_draft_segment WHERE channel_id = ? AND business_day = ?",
                ctx.channel, DAY);
        assertThat(segments).hasSize(1);
        String survivorId = (String) segments.get(0).get("id");
        List<Map<String, Object>> companions = jdbc.queryForList(
                "SELECT segment_id, seq_no FROM playout_draft_segment_companion"
                        + " WHERE channel_id = ? AND business_day = ? ORDER BY seq_no",
                ctx.channel, DAY);
        assertThat(companions).hasSize(2);
        assertThat(companions.get(0).get("segment_id")).isEqualTo(survivorId);
        assertThat(companions.get(1).get("segment_id")).isEqualTo(survivorId);
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、主素材 movie、伴随素材 song/spot、插播素材 news 及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private String songAsset;
        private String spotAsset;
        private String newsAsset;
        private long movieGrant;
        private long songGrant;
        private long spotGrant;
        private long newsGrant;
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.songAsset = ctx.prefix + "song";
        ctx.spotAsset = ctx.prefix + "spot";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.channel = ctx.prefix + "ch";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.songAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.spotAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset, GRANT_FROM, GRANT_TO);
        ctx.songGrant = createGrant(ctx.channel, ctx.songAsset, GRANT_FROM, GRANT_TO);
        ctx.spotGrant = createGrant(ctx.channel, ctx.spotAsset, GRANT_FROM, GRANT_TO);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset, GRANT_FROM, GRANT_TO);
        return ctx;
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions revokeGrant(long grantId, String requestId) throws Exception {
        return postJson("/api/grants/" + grantId + "/revoke", Map.of("requestId", requestId));
    }

    private ResultActions publish(String channel, String requestId, long draftVersion,
                                  long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                Map.of("requestId", requestId, "operator", "tester",
                        "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    private ResultActions playout(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/playout").param("at", at));
    }

    private static Map<String, Object> companion(String assetId, long grantId) {
        Map<String, Object> companion = new LinkedHashMap<>();
        companion.put("assetId", assetId);
        companion.put("grantId", grantId);
        return companion;
    }

    @SafeVarargs
    private static Map<String, Object> segment(String id, String assetId, String start, String end,
                                               Map<String, Object>... companions) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        if (companions.length > 0) {
            segment.put("companions", List.of(companions));
        }
        return segment;
    }

    @SafeVarargs
    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 Map<String, Object>... segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segments));
        return body;
    }

    private static Map<String, Object> overrideBody(String requestId, String overrideKey,
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

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
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
