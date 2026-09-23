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
 * 播出端版本租约 API 端到端测试：拉取绑定、续租、分段确认、快照一致性、版本引用、
 * 幂等与并发边界。运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务回滚由真实数据库验证。
 * 租约到期通过直接改写 expires_at_ms 模拟，避免依赖真实等待。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeaseApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_1000 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1100 = "2026-09-22T11:00:00.000+08:00";
    private static final String PLAY_1 = "2026-09-22T10:15:00.000+08:00";
    private static final String PLAY_2 = "2026-09-22T10:45:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程：拉取绑定、按序确认、全部确认后完成 ----------

    @Test
    void pullBindsLatestVersionAndCompletesAfterOrderedAcks() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));

        // 拉取：绑定版本 1，生成 epoch 1 与到期时刻，快照含两段
        MvcResult pulled = pull(ctx, "pull-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientKey").value(ctx.client))
                .andExpect(jsonPath("$.channelId").value(ctx.channel))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].segmentId").value(ctx.key("s1")))
                .andExpect(jsonPath("$.segments[0].acked").value(false))
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false))
                .andExpect(jsonPath("$.segments[1].segmentId").value(ctx.key("s2")))
                .andExpect(jsonPath("$.overrides.length()").value(0))
                .andReturn();
        long leaseId = readJson(pulled).get("leaseId").asLong();

        // 未过期重复拉取（新 requestId）：返回同一租约同一版本
        pull(ctx, "pull-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.leaseEpoch").value(1));

        // 按序确认第一段
        ack(leaseId, "ack-1", 1, ctx.key("s1"), PLAY_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackedCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.leaseStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.playedAt").value(PLAY_1));

        // 确认第二段后租约完成
        ack(leaseId, "ack-2", 1, ctx.key("s2"), PLAY_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackedCount").value(2))
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));

        // 租约明细：状态与确认进度
        getLease(leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.segments[0].acked").value(true))
                .andExpect(jsonPath("$.segments[0].ackKey").value("ack-1"))
                .andExpect(jsonPath("$.segments[1].playedAt").value(PLAY_2));

        // 确认记录查询（只读）
        getAcks(leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].ackKey").value("ack-1"))
                .andExpect(jsonPath("$[0].segmentId").value(ctx.key("s1")))
                .andExpect(jsonPath("$[1].ackKey").value("ack-2"));

        // COMPLETED 释放 ACTIVE 槽位：新拉取生成新租约，epoch 递增
        MvcResult again = pull(ctx, "pull-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.leaseEpoch").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        assertThat(readJson(again).get("leaseId").asLong()).isNotEqualTo(leaseId);
    }

    // ---------- 新发布不替换未过期租约；到期后绑定最新版本 ----------

    @Test
    void newPublicationDoesNotReplaceActiveLeaseAndBindsLatestAfterExpiry() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));
        long lease1 = leaseIdOf(pull(ctx, "p1"));

        // 发布版本 2
        publishProgram(ctx, "v2", 1, 2, 1,
                List.of(segment(ctx, "s3", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s4", ctx.newsAsset, T_1030, T_1100)));

        // 未过期重复拉取：仍是版本 1，新发布不得偷偷替换
        pull(ctx, "p2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(lease1))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segments[0].segmentId").value(ctx.key("s1")));

        // 续租推进 epoch，保持发布版本
        renew(ctx, lease1, "rn-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(2))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 租约到期后新拉取绑定最新版本 2，epoch 继续递增（历史最大为 2）
        expireLease(lease1);
        MvcResult pulled = pull(ctx, "p3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2))
                .andExpect(jsonPath("$.leaseEpoch").value(3))
                .andExpect(jsonPath("$.segments[0].segmentId").value(ctx.key("s3")))
                .andReturn();
        assertThat(readJson(pulled).get("leaseId").asLong()).isNotEqualTo(lease1);

        // 旧租约被惰性标记为 EXPIRED，历史快照不回写
        getLease(lease1)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    // ---------- 续租规则 ----------

    @Test
    void renewRulesAndIdempotency() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));
        long leaseId = leaseIdOf(pull(ctx, "p1"));

        // 纪元不符：409
        renew(ctx, leaseId, "rn-bad", 5)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EPOCH_MISMATCH"));

        // 正常续租：epoch 1 → 2
        renew(ctx, leaseId, "rn-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(2))
                .andExpect(jsonPath("$.publishedVersion").value(1));
        // 同 requestId 同参重放：返回首次结果
        renew(ctx, leaseId, "rn-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(2));
        // 同 requestId 异参：409
        renew(ctx, leaseId, "rn-1", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 续租后旧 epoch 确认：409
        ack(leaseId, "a-old", 1, ctx.key("s1"), PLAY_1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EPOCH_MISMATCH"));

        // 过期租约续租：409，且被惰性标记 EXPIRED
        expireLease(leaseId);
        renew(ctx, leaseId, "rn-2", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EXPIRED"));
        getLease(leaseId).andExpect(jsonPath("$.status").value("EXPIRED"));

        // 已完成租约续租：409
        long lease2 = leaseIdOf(pull(ctx, "p2"));
        ack(lease2, "c1", 3, ctx.key("s1"), PLAY_1).andExpect(status().isOk());
        ack(lease2, "c2", 3, ctx.key("s2"), PLAY_2).andExpect(status().isOk());
        getLease(lease2).andExpect(jsonPath("$.status").value("COMPLETED"));
        renew(ctx, lease2, "rn-3", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_NOT_ACTIVE"));
    }

    // ---------- 确认失败分支与整体回滚 ----------

    @Test
    void ackValidationBranchesAndRollback() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));
        long leaseId = leaseIdOf(pull(ctx, "p1"));

        // 跳段：409
        ack(leaseId, "k-skip", 1, ctx.key("s2"), PLAY_2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACK_OUT_OF_ORDER"));
        // 分段不属于快照：422
        ack(leaseId, "k-unk", 1, ctx.key("ghost"), PLAY_1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_NOT_IN_LEASE"));
        // playedAt 不在分段时窗：422
        ack(leaseId, "k-time", 1, ctx.key("s1"), PLAY_2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYED_AT_OUT_OF_WINDOW"));

        // 失败整体回滚：无确认记录、分段未确认、ackKey 未占用
        getAcks(leaseId).andExpect(jsonPath("$.length()").value(0));
        getLease(leaseId).andExpect(jsonPath("$.segments[0].acked").value(false));
        ack(leaseId, "k-time", 1, ctx.key("s1"), PLAY_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackedCount").value(1));

        // 重复同 ack：返回首次结果
        ack(leaseId, "k-time", 1, ctx.key("s1"), PLAY_1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackedCount").value(1))
                .andExpect(jsonPath("$.leaseStatus").value("ACTIVE"));
        // 同 ackKey 改时间：409
        ack(leaseId, "k-time", 1, ctx.key("s1"), "2026-09-22T10:20:00.000+08:00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACK_KEY_CONFLICT"));
        // 租约不存在：404
        ack(999999999, "k-x", 1, ctx.key("s1"), PLAY_1)
                .andExpect(status().isNotFound());

        // 过期租约确认：409，且被惰性标记 EXPIRED
        expireLease(leaseId);
        ack(leaseId, "k-exp", 1, ctx.key("s2"), PLAY_2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EXPIRED"));
        getLease(leaseId).andExpect(jsonPath("$.status").value("EXPIRED"));

        // 已完成租约：重放最终确认返回首次结果，新 ackKey 则 409
        long lease2 = leaseIdOf(pull(ctx, "p2"));
        ack(lease2, "f1", 2, ctx.key("s1"), PLAY_1).andExpect(status().isOk());
        ack(lease2, "f2", 2, ctx.key("s2"), PLAY_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));
        ack(lease2, "f2", 2, ctx.key("s2"), PLAY_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));
        ack(lease2, "f3", 2, ctx.key("s1"), PLAY_1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_NOT_ACTIVE"));
    }

    // ---------- 拉取幂等与失败不占键 ----------

    @Test
    void pullIdempotencyAndFailureDoesNotConsumeRequestId() throws Exception {
        Ctx ctx = newContext();

        // 无已发布版本：422，失败不占 requestId
        pull(ctx, "p-fail")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_PUBLISHED_VERSION"));

        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));

        // 同 requestId 失败后重试成功
        long leaseId = leaseIdOf(pull(ctx, "p-fail").andExpect(status().isOk()));
        // 同 requestId 同参重放：返回首次结果（同一租约）
        pull(ctx, "p-fail")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId));
        // 同 requestId 异参（租约时长不同）：409
        pullRaw(ctx.prefix + "p-fail", ctx.client, ctx.channel, DAY, 300000L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 异参（客户端不同）：409
        pullRaw(ctx.prefix + "p-fail", ctx.client + "-x", ctx.channel, DAY, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 参数校验：缺 requestId 400、业务日格式错误 400、时长超限 400
        mvc.perform(post("/api/edge/pulls").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientKey", ctx.client, "channelId", ctx.channel,
                                "businessDay", DAY))))
                .andExpect(status().isBadRequest());
        pullRaw("p-bad-day", ctx.client, ctx.channel, "2026/09/22", null)
                .andExpect(status().isBadRequest());
        pullRaw("p-bad-ttl", ctx.client, ctx.channel, DAY, 7200000L)
                .andExpect(status().isBadRequest());
    }

    // ---------- 快照一致且不回写 ----------

    @Test
    void snapshotCapturesGrantDecisionsAndOverridesAndIsNotRewritten() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));

        // 拉取前：ACTIVE 插播一条；news 授权已撤销（快照应记录 grantRevoked=true）
        createOverride(ctx, "ov-1", ctx.alertAsset, ctx.alertGrant, 5,
                "2026-09-22T10:15:00.000+08:00", "2026-09-22T10:45:00.000+08:00")
                .andExpect(status().isOk());
        postJson("/api/grants/" + ctx.newsGrant + "/revoke",
                        Map.of("requestId", ctx.prefix + "rv-news"))
                .andExpect(status().isOk());

        long leaseId = leaseIdOf(pull(ctx, "p1")
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false))
                .andExpect(jsonPath("$.segments[1].grantRevoked").value(true))
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0].overrideKey").value(ctx.key("ov-1")))
                .andExpect(jsonPath("$.overrides[0].grantRevoked").value(false)));

        // 拉取后：插播授权撤销、插播取消，旧租约快照不回写
        postJson("/api/grants/" + ctx.alertGrant + "/revoke",
                        Map.of("requestId", ctx.prefix + "rv-alert"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides/" + ctx.key("ov-1") + "/cancel",
                        Map.of("requestId", ctx.prefix + "cancel-ov"))
                .andExpect(status().isOk());

        getLease(leaseId)
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0].grantRevoked").value(false))
                .andExpect(jsonPath("$.segments[1].grantRevoked").value(true))
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false));
    }

    // ---------- 版本引用查询 ----------

    @Test
    void publicationReferencesReflectUnexpiredActiveLeases() throws Exception {
        Ctx ctx = newContext();
        long publicationId = publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));

        // 无租约引用：可清理
        references(publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.activeLeaseCount").value(0))
                .andExpect(jsonPath("$.cleanable").value(true));

        // 未过期 ACTIVE 租约引用：不可清理
        long leaseId = leaseIdOf(pull(ctx, "p1"));
        references(publicationId)
                .andExpect(jsonPath("$.activeLeaseCount").value(1))
                .andExpect(jsonPath("$.cleanable").value(false));

        // 租约到期：恢复可清理
        expireLease(leaseId);
        references(publicationId)
                .andExpect(jsonPath("$.activeLeaseCount").value(0))
                .andExpect(jsonPath("$.cleanable").value(true));

        // 全部确认完成的租约同样不阻塞清理
        long lease2 = leaseIdOf(pull(ctx, "p2"));
        ack(lease2, "r1", 2, ctx.key("s1"), PLAY_1).andExpect(status().isOk());
        ack(lease2, "r2", 2, ctx.key("s2"), PLAY_2).andExpect(status().isOk());
        references(publicationId)
                .andExpect(jsonPath("$.activeLeaseCount").value(0))
                .andExpect(jsonPath("$.cleanable").value(true));

        // 发布快照不存在：404
        references(999999999).andExpect(status().isNotFound());
    }

    // ---------- 并发：同客户端重复拉取只生成一个 ACTIVE 租约 ----------

    @Test
    void concurrentPullsCreateSingleActiveLease() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));

        List<MvcResult> results = runConcurrently(2, i -> pullRaw(
                "cc-pull-" + ctx.prefix + i, ctx.client, ctx.channel, DAY, null).andReturn());
        assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
        assertThat(results.get(1).getResponse().getStatus()).isEqualTo(200);
        long lease0 = readJson(results.get(0)).get("leaseId").asLong();
        long lease1 = readJson(results.get(1)).get("leaseId").asLong();
        assertThat(lease0).isEqualTo(lease1);

        // 最终库内状态：该客户端+频道+业务日恰好一个 ACTIVE 租约、一条租约记录
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease"
                        + " WHERE client_key = ? AND channel_id = ? AND active_unique = 1",
                Integer.class, ctx.client, ctx.channel);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease WHERE client_key = ? AND channel_id = ?",
                Integer.class, ctx.client, ctx.channel);
        assertThat(active).isEqualTo(1);
        assertThat(total).isEqualTo(1);
    }

    // ---------- 并发：同分段并发确认只有一条成功 ----------

    @Test
    void concurrentAcksOnSameSegmentOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        publishProgram(ctx, "v1", 0, 1, 0,
                List.of(segment(ctx, "s1", ctx.movieAsset, T_1000, T_1030),
                        segment(ctx, "s2", ctx.newsAsset, T_1030, T_1100)));
        long leaseId = leaseIdOf(pull(ctx, "p1"));

        List<Integer> statuses = runConcurrently(2, i -> ack(leaseId,
                        "cc-ack-" + ctx.prefix + i, 1, ctx.key("s1"), PLAY_1)
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 最终库内状态：分段只被确认一次，确认记录恰好一条
        Integer ackCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM playout_lease_ack WHERE lease_id = ?",
                Integer.class, leaseId);
        assertThat(ackCount).isEqualTo(1);
        getLease(leaseId)
                .andExpect(jsonPath("$.segments[0].acked").value(true))
                .andExpect(jsonPath("$.segments[1].acked").value(false));

        // 后续分段仍可确认
        ack(leaseId, "cc-next", 1, ctx.key("s2"), PLAY_2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：保底素材、普通素材 movie/news/alert、频道、边缘客户端键及各自全天授权。 */
    private final class Ctx {
        private final String prefix = "l" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String client;
        private String fallbackAsset;
        private String movieAsset;
        private String newsAsset;
        private String alertAsset;
        private long movieGrant;
        private long newsGrant;
        private long alertGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.newsAsset = ctx.prefix + "news";
        ctx.alertAsset = ctx.prefix + "alert";
        ctx.channel = ctx.prefix + "ch";
        ctx.client = ctx.prefix + "edge";

        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.newsAsset, "durationMs", 1800000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.alertAsset, "durationMs", 900000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        ctx.movieGrant = createGrant(ctx.channel, ctx.movieAsset);
        ctx.newsGrant = createGrant(ctx.channel, ctx.newsAsset);
        ctx.alertGrant = createGrant(ctx.channel, ctx.alertAsset);
        return ctx;
    }

    private static Map<String, Object> segment(Ctx ctx, String shortId, String assetId,
                                               String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.key(shortId));
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    /** 整份替换草稿并发布，返回发布快照 ID。 */
    private long publishProgram(Ctx ctx, String suffix, long expectedDraftVersion,
                                long draftVersion, long expectedPublishedVersion,
                                List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", "draft-" + ctx.prefix + suffix);
        draftBody.put("expectedDraftVersion", expectedDraftVersion);
        draftBody.put("segments", segments);
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "pub-" + ctx.prefix + suffix,
                                "draftVersion", draftVersion,
                                "expectedPublishedVersion", expectedPublishedVersion))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(published).get("publicationId").asLong();
    }

    private long createGrant(String channel, String asset) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private ResultActions createOverride(Ctx ctx, String shortKey, String assetId, long grantId,
                                         int priority, String start, String end) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "ov-req-" + ctx.key(shortKey));
        body.put("overrideKey", ctx.key(shortKey));
        body.put("channelId", ctx.channel);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return postJson("/api/emergency-overrides", body);
    }

    private ResultActions pull(Ctx ctx, String requestId) throws Exception {
        return pullRaw(ctx.prefix + requestId, ctx.client, ctx.channel, DAY, null);
    }

    private ResultActions pullRaw(String requestId, String clientKey, String channelId,
                                  String businessDay, Long leaseTtlMs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("clientKey", clientKey);
        body.put("channelId", channelId);
        body.put("businessDay", businessDay);
        if (leaseTtlMs != null) {
            body.put("leaseTtlMs", leaseTtlMs);
        }
        return postJson("/api/edge/pulls", body);
    }

    private ResultActions renew(Ctx ctx, long leaseId, String requestId, long leaseEpoch)
            throws Exception {
        return postJson("/api/edge/leases/" + leaseId + "/renew",
                Map.of("requestId", ctx.prefix + requestId, "leaseEpoch", leaseEpoch));
    }

    private ResultActions ack(long leaseId, String ackKey, long leaseEpoch,
                              String segmentId, String playedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ackKey", ackKey);
        body.put("leaseEpoch", leaseEpoch);
        body.put("segmentId", segmentId);
        body.put("playedAt", playedAt);
        return postJson("/api/edge/leases/" + leaseId + "/acks", body);
    }

    private ResultActions getLease(long leaseId) throws Exception {
        return mvc.perform(get("/api/edge/leases/" + leaseId));
    }

    private ResultActions getAcks(long leaseId) throws Exception {
        return mvc.perform(get("/api/edge/leases/" + leaseId + "/acks"));
    }

    private ResultActions references(long publicationId) throws Exception {
        return mvc.perform(get("/api/publications/" + publicationId + "/references"));
    }

    /** 将租约到期时刻改写为过去，模拟租约到期（避免真实等待）。 */
    private void expireLease(long leaseId) {
        jdbc.update("UPDATE playout_lease SET expires_at_ms = 1 WHERE id = ?", leaseId);
    }

    private long leaseIdOf(ResultActions actions) throws Exception {
        return readJson(actions.andReturn()).get("leaseId").asLong();
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
