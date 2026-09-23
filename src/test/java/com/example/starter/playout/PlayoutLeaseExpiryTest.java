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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租约过期边界测试：以 playout.lease-ttl-ms=0 确定性触发到期，验证过期后确认/续租拒绝、
 * 新拉取绑定最新版本且 leaseEpoch 递增、版本引用解除。运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest(properties = "playout.lease-ttl-ms=0")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutLeaseExpiryTest {

    private static final String DAY = "2026-09-23";
    private static final String T_10 = "2026-09-23T10:00:00.000+08:00";
    private static final String T_1030 = "2026-09-23T10:30:00.000+08:00";
    private static final String T_11 = "2026-09-23T11:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-23T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-24T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void expiredLeaseRejectsAckAndRenewAndNewPullBindsLatestVersion() throws Exception {
        String p = prefix();
        String channel = newChannelWithGrant(p);
        String client = p + "client";
        replaceDraft(channel, p, 0, "req-" + p + "-d1",
                segment(p + "seg-1", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p1", 1, 0);

        // TTL=0：租约创建即到期
        MvcResult first = postJson("/api/edge-leases",
                        pullBody("req-" + p + "-pull1", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andReturn();
        long leaseId = readJson(first).get("leaseId").asLong();

        // 过期租约确认：409 LEASE_EXPIRED
        postJson("/api/edge-leases/" + leaseId + "/acks",
                        ackBody("ack-" + p + "-1", leaseId, 1, p + "seg-1", T_1030))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EXPIRED"));
        // 过期租约续租：409 LEASE_EXPIRED
        postJson("/api/edge-leases/" + leaseId + "/renew",
                        Map.of("requestId", "req-" + p + "-renew"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LEASE_EXPIRED"));
        // 只读查询按 EXPIRED 返回
        getJson("/api/edge-leases/" + leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        // 发布版本 2 后再次拉取：旧租约作废，新租约绑定最新版本且 epoch 递增
        replaceDraft(channel, p, 1, "req-" + p + "-d2",
                segment(p + "seg-2", p + "movie", T_10, T_11));
        publish(channel, p, "req-" + p + "-p2", 2, 1);
        MvcResult second = postJson("/api/edge-leases",
                        pullBody("req-" + p + "-pull2", client, channel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEpoch").value(2))
                .andExpect(jsonPath("$.publishedVersion").value(2))
                .andExpect(jsonPath("$.segments[0].segmentId").value(p + "seg-2"))
                .andReturn();
        long secondLeaseId = readJson(second).get("leaseId").asLong();
        org.assertj.core.api.Assertions.assertThat(secondLeaseId).isNotEqualTo(leaseId);

        // 旧租约已转为 EXPIRED，不再引用版本 1（两个租约均已过期，两个版本均可清理）
        getJson("/api/edge-leases/" + leaseId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        getJson("/api/channels/" + channel + "/drafts/" + DAY + "/publication-references")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].publishedVersion").value(1))
                .andExpect(jsonPath("$[0].cleanable").value(true))
                .andExpect(jsonPath("$[1].publishedVersion").value(2))
                .andExpect(jsonPath("$[1].cleanable").value(true));
    }

    // ---------- 测试辅助 ----------

    private String newChannelWithGrant(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "movie",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk());
        return p + "ch";
    }

    private void replaceDraft(String channel, String p, long expectedVersion, String requestId,
                              Map<String, Object> segment) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", List.of(segment));
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
}
