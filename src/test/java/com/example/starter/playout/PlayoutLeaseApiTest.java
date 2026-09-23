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
 * 播出端版本租约 API 端到端测试：拉取绑定、分段确认、续租、只读查询、幂等与并发边界。
 * 运行环境为 H2（MySQL 兼容模式）内存库，表结构与生产 MySQL DDL 一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutLeaseApiTest {

    private static final String DAY = "2026-09-23";
    private static final String T_10 = "2026-09-23T10:00:00.000+08:00";
    private static final String T_1030 = "2026-09-23T10:30:00.000+08:00";
    private static final String T_11 = "2026-09-23T11:00:00.000+08:00";
    private static final String T_1130 = "2026-09-23T11:30:00.000+08:00";
    private static final String T_12 = "2026-09-23T12:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-23T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-24T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 拉取主流程与版本绑定 ----------

    @Test
    void pullBindsLatestVersionAndActiveLeaseKeepsVersion() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);

        // 首次拉取：绑定最新发布版本 1，生成 leaseEpoch=1 与 expiresAt，快照含完整分段
        MvcResult first = postJson("/api/edge-leases",
                        pullBody("req-" + p + "-pull1", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientKey").value(client))
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].seq").value(1))
                .andExpect(jsonPath("$.segments[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false))
                .andExpect(jsonPath("$.segments[0].start").value(T_10))
                .andExpect(jsonPath("$.overrides.length()").value(0))
                .andReturn();
        long leaseId = readJson(first).get("leaseId").asLong();

        // 新发布版本 2：未过期 ACTIVE 租约不得被偷偷替换
        replaceDraft(channel, p, 1, "req-" + p + "-d2",
                segment(p + "seg-2", p + "movie", T_11, T_12));
        publish(channel, p, "req-" + p + "-p2", 2, 1);

        // 重复拉取（新 requestId）：仍返回同一租约同一版本
        postJson("/api/edge-leases", pullBody("req-" + p + "-pull2", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId))
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segments[0].segmentId").value(p + "seg-1"));

        // requestId 幂等：同键同参返回首次结果
        postJson("/api/edge-leases", pullBody("req-" + p + "-pull1", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId))
                .andExpect(jsonPath("$.leaseEpoch").value(1));
        // 同 requestId 改参数：409
        postJson("/api/edge-leases", Map.of("requestId", "req-" + p + "-pull1",
                        "clientKey", client, "channelId", channel, "businessDay", "2026-09-24"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 版本引用查询：v1 被未过期 ACTIVE 租约引用不可清理，v2 无引用可清理
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/publication-references")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].publishedVersion").value(1))
                .andExpect(jsonPath("$[0].activeLeaseCount").value(1))
                .andExpect(jsonPath("$[0].cleanable").value(false))
                .andExpect(jsonPath("$[1].publishedVersion").value(2))
                .andExpect(jsonPath("$[1].activeLeaseCount").value(0))
                .andExpect(jsonPath("$[1].cleanable").value(true));
    }

    @Test
    void pullFailureBranchesAndFailureDoesNotOccupyRequestId() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";

        // 422：无已发布版本
        postJson("/api/edge-leases", pullBody("req-" + p + "-pull", client, channel))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_PUBLISHED_VERSION"));
        // 404：频道不存在
        postJson("/api/edge-leases", pullBody("req-" + p + "-ghost", client, p + "ghost"))
                .andExpect(status().isNotFound());

        // 失败不占键：发布后用同一 requestId 拉取成功
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);
        postJson("/api/edge-leases", pullBody("req-" + p + "-pull", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 不同客户端同一业务日可各自持有 ACTIVE 租约
        postJson("/api/edge-leases", pullBody("req-" + p + "-pull-b", p + "client-b", channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(1));
    }

    // ---------- 快照一致性：发布/授权/插播与拉取形成一致快照，历史不回写 ----------

    @Test
    void pullSnapshotConsistentAndHistoryNotRewritten() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        long grantId = lastGrantId;
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);

        // 拉取前存在 ACTIVE 插播：应进入快照
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov", "overrideKey", p + "ov-1",
                        "channelId", channel, "assetId", p + "movie", "grantId", grantId,
                        "priority", 5, "start", "2026-09-23T10:15:00.000+08:00",
                        "end", "2026-09-23T10:45:00.000+08:00"))
                .andExpect(status().isOk());

        MvcResult pulled = postJson("/api/edge-leases",
                        pullBody("req-" + p + "-pull", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0].overrideKey").value(p + "ov-1"))
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false))
                .andReturn();
        long leaseId = readJson(pulled).get("leaseId").asLong();

        // 拉取后取消插播、撤销授权：旧租约快照不回写
        postJson("/api/emergency-overrides/" + p + "ov-1/cancel",
                        Map.of("requestId", "req-" + p + "-ovc"))
                .andExpect(status().isOk());
        postJson("/api/grants/" + grantId + "/revoke", Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk());

        getJson("/api/edge-leases/" + leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0].overrideKey").value(p + "ov-1"))
                .andExpect(jsonPath("$.segments[0].grantRevoked").value(false));

        // 确认全部分段后租约 COMPLETED，发布版本不再被未过期 ACTIVE 租约引用
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-1", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/publication-references")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cleanable").value(true));
    }

    // ---------- 分段确认 ----------

    @Test
    void ackInOrderCompletesLeaseAndIsIdempotent() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11),
                segment(p + "seg-2", p + "movie", T_11, T_12));
        publish(channel, p, "req-" + p + "-p1", 1, 0);
        long leaseId = pullLease(client, channel, "req-" + p + "-pull");

        // 边界：playedAt 等于分段起点（含）合法
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-1", leaseId, 1, p + "seg-1", T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.confirmedCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.leaseStatus").value("ACTIVE"));

        // 重复同 ack：返回首次结果，不重复确认
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-1", leaseId, 1, p + "seg-1", T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.confirmedCount").value(1));
        // 同 ackKey 改时间：409
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-1", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACK_KEY_CONFLICT"));

        // 确认最后一段：租约 COMPLETED
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-2", leaseId, 1, p + "seg-2", T_1130))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));

        getJson("/api/edge-leases/" + leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        getJson("/api/edge-leases/" + leaseId + "/acks")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$[1].segmentId").value(p + "seg-2"))
                .andExpect(jsonPath("$[1].leaseStatus").value("COMPLETED"));

        // 已完成后继续确认：409
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-3", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_NOT_ACTIVE"));
    }

    @Test
    void ackRejectsOutOfOrderWindowViolationAndFailureRollsBack() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11),
                segment(p + "seg-2", p + "movie", T_11, T_12));
        publish(channel, p, "req-" + p + "-p1", 1, 0);
        long leaseId = pullLease(client, channel, "req-" + p + "-pull");

        // 409：跳段确认
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-skip", leaseId, 1, p + "seg-2", T_1130))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SEGMENT_OUT_OF_ORDER"));

        // 422：playedAt 落在其他分段时窗
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-bad-time", leaseId, 1, p + "seg-1", T_1130))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYED_AT_OUT_OF_WINDOW"));
        // 422：playedAt 等于分段终点（不含）
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-at-end", leaseId, 1, p + "seg-1", T_11))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYED_AT_OUT_OF_WINDOW"));

        // 失败不占键：同一 ackKey 修正时间后成功，且整体回滚未产生部分确认
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-bad-time", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackKey").value("ack-" + p + "-bad-time"))
                .andExpect(jsonPath("$.seq").value(1));
        getJson("/api/edge-leases/" + leaseId + "/acks")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 404：租约不存在
        postJson("/api/edge-leases/999999/acks",
                        ackBody("ack-" + p + "-ghost", 999999, 1, p + "seg-1", T_1030))
                .andExpect(status().isNotFound());
        // 400：路径与请求体 leaseId 不一致
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-mismatch", leaseId + 1, 1, p + "seg-2", T_1130))
                .andExpect(status().isBadRequest());
    }

    // ---------- 续租 ----------

    @Test
    void renewAdvancesEpochKeepsVersionAndRejectsOldEpochAck() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);
        long leaseId = pullLease(client, channel, "req-" + p + "-pull");

        // 续租主流程：epoch 推进、版本保持、到期时间延长
        postJson("/api/edge-leases/" + leaseId + "/renew",
                        Map.of("requestId", "req-" + p + "-renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseId").value(leaseId))
                .andExpect(jsonPath("$.leaseEpoch").value(2))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 幂等重试：返回首次结果，不重复推进
        postJson("/api/edge-leases/" + leaseId + "/renew",
                        Map.of("requestId", "req-" + p + "-renew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(2));
        // 同 requestId 用于不同租约：409
        postJson("/api/edge-leases/" + (leaseId + 1) + "/renew",
                        Map.of("requestId", "req-" + p + "-renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 旧 epoch 确认：409；当前 epoch 确认：成功
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-old", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EPOCH_MISMATCH"));
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-new", leaseId, 2, p + "seg-1", T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseStatus").value("COMPLETED"));

        // 已 COMPLETED 租约不能续租
        postJson("/api/edge-leases/" + leaseId + "/renew",
                        Map.of("requestId", "req-" + p + "-renew2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_NOT_ACTIVE"));
        // 404：租约不存在
        postJson("/api/edge-leases/999999/renew", Map.of("requestId", "req-" + p + "-renew3"))
                .andExpect(status().isNotFound());
    }

    // ---------- 只读查询 ----------

    @Test
    void readOnlyQueriesAndNotFoundBranches() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        getJson("/api/edge-leases/999999").andExpect(status().isNotFound());
        getJson("/api/edge-leases/999999/acks").andExpect(status().isNotFound());
        getJson("/api/channels/" + p + "ghost/drafts/" + DAY + "/publication-references")
                .andExpect(status().isNotFound());

        // 无发布版本时引用查询返回空列表
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/publication-references")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentPullsSameClientShareSingleActiveLease() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);

        // 并发拉取（不同 requestId）：都成功且返回同一租约
        List<MvcResult> results = runConcurrently(2, i -> postJson("/api/edge-leases",
                        pullBody("req-" + p + "-pull-" + i, client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andReturn());
        long firstLease = readJson(results.get(0)).get("leaseId").asLong();
        long secondLease = readJson(results.get(1)).get("leaseId").asLong();
        assertThat(secondLease).isEqualTo(firstLease);
    }

    @Test
    void concurrentSameAckKeyReturnsFirstResultOnly() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);
        long leaseId = pullLease(client, channel, "req-" + p + "-pull");

        // 并发同 ackKey 同参数：均返回首次结果，只产生一条确认记录
        List<MvcResult> results = runConcurrently(2, i -> postJson(
                        "/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-c", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(1))
                .andReturn());
        assertThat(readJson(results.get(0)).get("ackKey").asText())
                .isEqualTo(readJson(results.get(1)).get("ackKey").asText());
        getJson("/api/edge-leases/" + leaseId + "/acks")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
        MvcResult grant = postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk())
                .andReturn();
        lastGrantId = readJson(grant).get("id").asLong();
        return p + "ch";
    }

    private void replaceDraft(String channel, String p, long expectedVersion, String requestId,
                              Map<String, Object>... segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segments));
        putJson("/api/channels/" + channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk());
    }

    private void publish(String channel, String p, String requestId, long draftVersion,
                         long expectedPublishedVersion) throws Exception {
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", requestId, "draftVersion", draftVersion,
                                "expectedPublishedVersion", expectedPublishedVersion))
                .andExpect(status().isOk());
    }

    /** 拉取租约并返回 leaseId。 */
    private long pullLease(String client, String channel, String requestId) throws Exception {
        MvcResult result = postJson("/api/edge-leases",
                        pullBody(requestId, client, channel))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("leaseId").asLong();
    }

    private static Map<String, Object> pullBody(String requestId, String clientKey,
                                                String channelId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("clientKey", clientKey);
        body.put("channelId", channelId);
        body.put("businessDay", DAY);
        return body;
    }

    private static Map<String, Object> ackBody(String ackKey, long leaseId, long leaseEpoch,
                                               String segmentId, String playedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ackKey", ackKey);
        body.put("leaseId", leaseId);
        body.put("leaseEpoch", leaseEpoch);
        body.put("segmentId", segmentId);
        body.put("playedAt", playedAt);
        return body;
    }

    private static Map<String, Object> segment(String id, String assetId, String start,
                                               String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
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
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingSupplier {
        MvcResult get(int index) throws Exception;
    }

    private static List<MvcResult> runConcurrently(int threads, ThrowingSupplier action)
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
