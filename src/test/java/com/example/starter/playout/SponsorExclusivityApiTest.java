package com.example.starter.playout;

import com.example.starter.playout.api.JacksonConfig;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 播出赞助排他端到端测试：窗口登记/修订/删除、发布与批量发布排他校验、紧急插播校验、
 * 发布撤销、快照固化、幂等与并发边界。运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SponsorExclusivityApiTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DAY = "2026-09-22";
    private static final String T_09 = "2026-09-22T09:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_12 = "2026-09-22T12:00:00.000+08:00";
    private static final String T_13 = "2026-09-22T13:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 素材 sponsorKey ----------

    @Test
    void assetCarriesSponsorKey() throws Exception {
        String p = prefix();
        postJson("/api/assets", Map.of("id", p + "a1", "durationMs", 1000, "sponsorKey", "SP-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sponsorKey").value("SP-A"));
        postJson("/api/assets", Map.of("id", p + "a2", "durationMs", 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sponsorKey").value(nullValue()));
    }

    // ---------- 窗口登记规则 ----------

    @Test
    void windowRegistrationRules() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");

        // 主流程：登记成功，版本 1，频道集合按字典序返回
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowKey").value(p + "w1"))
                .andExpect(jsonPath("$.sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.channelIds[0]").value(channel))
                .andExpect(jsonPath("$.start").value(T_10))
                .andExpect(jsonPath("$.end").value(T_12));

        // 422：频道集合为空
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-e1", p + "w-e1", "SP-A", List.of(), T_10, T_12))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_CHANNELS_EMPTY"));
        // 422：时间非法（终点不大于起点）
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-e2", p + "w-e2", "SP-A", List.of(channel), T_12, T_10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_TIME_INVALID"));
        // 422：窗口跨出运营日
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-e3", p + "w-e3", "SP-A", List.of(channel),
                                T_10, "2026-09-23T00:00:00.001+08:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_CROSSES_DAY"));
        // 404：频道不存在
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-e4", p + "w-e4", "SP-A", List.of(p + "ghost"),
                                T_10, T_12))
                .andExpect(status().isNotFound());

        // 422：同赞助商同频道窗口重叠
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-ov", p + "w-ov", "SP-A", List.of(channel), T_11, T_13))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));
        // 边界：相邻（起点等于前一窗口终点）合法
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-adj", p + "w-adj", "SP-A", List.of(channel), T_12, T_13))
                .andExpect(status().isOk());
        // 不同赞助商同频道同区间可并存
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-other", p + "w-other", "SP-B", List.of(channel),
                                T_10, T_12))
                .andExpect(status().isOk());
        // 同赞助商不同频道同区间可并存
        String channel2 = newChannel(p, "ch2");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-ch2", p + "w-ch2", "SP-A", List.of(channel2),
                                T_10, T_12))
                .andExpect(status().isOk());

        // 409：窗口键重复
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-dup", p + "w1", "SP-A", List.of(channel), T_09, T_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_WINDOW_KEY"));

        // 幂等：同 requestId 同参数重放首次结果
        Map<String, Object> body = windowBody("req-" + p + "-idem", p + "w-idem", "SP-C",
                List.of(channel), T_09, T_10);
        postJson("/api/sponsor-windows", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        postJson("/api/sponsor-windows", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowKey").value(p + "w-idem"));
        // 同 requestId 异参数：409
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-idem", p + "w-idem2", "SP-C", List.of(channel),
                                T_09, T_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 失败不占键：先 422 失败，再用同 requestId 成功
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-retry", p + "w-retry", "SP-C", List.of(), T_09, T_10))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-retry", p + "w-retry", "SP-C", List.of(channel),
                                T_10, T_11))
                .andExpect(status().isOk());
    }
    // ---------- 窗口修订与删除 ----------

    @Test
    void windowReviseAndDelete() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_11))
                .andExpect(status().isOk());
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w2", p + "w2", "SP-A", List.of(channel), T_12, T_13))
                .andExpect(status().isOk());

        // 409：期望版本不符
        putJson("/api/sponsor-windows/" + p + "w1",
                        reviseBody("req-" + p + "-rv0", 5, List.of(channel), T_10, T_12))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WINDOW_VERSION_CONFLICT"));
        // 422：修订后与其他窗口重叠
        putJson("/api/sponsor-windows/" + p + "w1",
                        reviseBody("req-" + p + "-rv1", 1, List.of(channel), T_11, T_13))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));
        // 修订主流程：版本 +1，旧版本被取代
        putJson("/api/sponsor-windows/" + p + "w1",
                        reviseBody("req-" + p + "-rv2", 1, List.of(channel), T_09, T_11))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.start").value(T_09))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getJson("/api/sponsor-windows/" + p + "w1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 删除主流程：状态 DELETED，历史仍可查询
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", "req-" + p + "-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.version").value(2));
        getJson("/api/sponsor-windows/" + p + "w1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        // 幂等重放：同 requestId 返回首次结果
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", "req-" + p + "-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        // 409：已删除再删 / 再修订
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", "req-" + p + "-d2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WINDOW_NOT_ACTIVE"));
        putJson("/api/sponsor-windows/" + p + "w1",
                        reviseBody("req-" + p + "-rv3", 2, List.of(channel), T_09, T_10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WINDOW_NOT_ACTIVE"));
        // 404：从未存在的窗口
        getJson("/api/sponsor-windows/" + p + "ghost")
                .andExpect(status().isNotFound());

        // 删除后该区间不再排他：异赞助素材可发布
        newAsset(p + "movieB", "SP-B");
        newGrant(channel, p + "movieB");
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-dr", 0, segment(p + "s1", p + "movieB", T_09, T_10)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 诊断查询：按赞助商与运营日列出 ACTIVE 窗口
        getJson("/api/sponsor-windows?sponsorKey=SP-A&businessDay=" + DAY + "&channelId=" + channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].windowKey").value(p + "w2"));
    }

    // ---------- 发布排他校验与快照固化 ----------

    @Test
    void publishValidatesExclusivityAndFreezesSnapshot() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA1", "SP-A");
        newAsset(p + "movieA2", "SP-A");
        newAsset(p + "movieB", "SP-B");
        newAsset(p + "plain", null);
        newGrant(channel, p + "movieA1");
        newGrant(channel, p + "movieA2");
        newGrant(channel, p + "movieB");
        newGrant(channel, p + "plain");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_12))
                .andExpect(status().isOk());

        // 409：窗口内排入异赞助素材，返回频道、时段、冲突素材与交集时长
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieB", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].channelId").value(channel))
                .andExpect(jsonPath("$.conflicts[0].segmentId").value(p + "s1"))
                .andExpect(jsonPath("$.conflicts[0].assetId").value(p + "movieB"))
                .andExpect(jsonPath("$.conflicts[0].assetSponsorKey").value("SP-B"))
                .andExpect(jsonPath("$.conflicts[0].windowKey").value(p + "w1"))
                .andExpect(jsonPath("$.conflicts[0].windowSponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.conflicts[0].conflictStart").value(T_10))
                .andExpect(jsonPath("$.conflicts[0].conflictEnd").value(T_11))
                .andExpect(jsonPath("$.conflicts[0].overlapMs").value(3600000));
        // 冲突发布不占版本：修正后仍可以 expectedPublishedVersion=0 发布
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d2", 1, List.of(
                                segment(p + "s1", p + "movieA1", T_10, T_11),
                                segment(p + "s2", p + "movieA2", T_11, T_12),
                                segment(p + "s3", p + "movieB", T_12, T_13))))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 2,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 快照固化：同赞助商不同素材并存，窗口外异赞助合法；命中窗口版本与素材 sponsorKey 落库
        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.segments[0].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowKey").value(p + "w1"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowVersion").value(1))
                .andExpect(jsonPath("$.segments[1].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[1].sponsorWindowKey").value(p + "w1"))
                .andExpect(jsonPath("$.segments[2].sponsorKey").value("SP-B"))
                .andExpect(jsonPath("$.segments[2].sponsorWindowKey").value(nullValue()))
                .andExpect(jsonPath("$.segments[2].sponsorWindowVersion").value(nullValue()));

        // 409：无赞助素材同样不得进入异赞助窗口
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d3", 2, segment(p + "s1", p + "plain", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p3", "draftVersion", 3,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].assetSponsorKey").value(nullValue()));
    }
    // ---------- 跨频道批量发布：完整最终编排校验，整体提交或整体回滚 ----------

    @Test
    void batchPublishIsAtomicAcrossChannels() throws Exception {
        String p = prefix();
        String ch1 = newChannel(p, "ch1");
        String ch2 = newChannel(p, "ch2");
        newAsset(p + "movieA", "SP-A");
        newAsset(p + "movieB", "SP-B");
        newGrant(ch1, p + "movieA");
        newGrant(ch1, p + "movieB");
        newGrant(ch2, p + "movieA");
        newGrant(ch2, p + "movieB");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(ch2), T_10, T_12))
                .andExpect(status().isOk());

        putJson("/api/channels/" + ch1 + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieA", T_10, T_11)))
                .andExpect(status().isOk());
        putJson("/api/channels/" + ch2 + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d2", 0, segment(p + "s2", p + "movieB", T_10, T_11)))
                .andExpect(status().isOk());

        // ch2 有异赞助冲突：整次 409，ch1 不得留下部分更新
        postJson("/api/publications/publish-batch",
                        batchBody("req-" + p + "-b1",
                                batchItem(ch1, 1, 0), batchItem(ch2, 1, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].channelId").value(ch2))
                .andExpect(jsonPath("$.conflicts[0].assetId").value(p + "movieB"));
        // ch1 未发布：以 expectedPublishedVersion=0 单发仍成功
        postJson("/api/channels/" + ch1 + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-solo", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 失败不占键：修正 ch2 草稿后同 requestId 重发成功
        putJson("/api/channels/" + ch2 + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d3", 1, segment(p + "s2", p + "movieA", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/publications/publish-batch",
                        batchBody("req-" + p + "-b1",
                                batchItem(ch1, 1, 1), batchItem(ch2, 2, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].channelId").value(ch1))
                .andExpect(jsonPath("$.items[0].publishedVersion").value(2))
                .andExpect(jsonPath("$.items[1].channelId").value(ch2))
                .andExpect(jsonPath("$.items[1].publishedVersion").value(1));
        // 幂等重放：同 requestId 同参数返回首次结果
        postJson("/api/publications/publish-batch",
                        batchBody("req-" + p + "-b1",
                                batchItem(ch1, 1, 1), batchItem(ch2, 2, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].publishedVersion").value(1));
        // 同 requestId 异参数：409
        postJson("/api/publications/publish-batch",
                        batchBody("req-" + p + "-b1",
                                batchItem(ch1, 1, 2), batchItem(ch2, 2, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 400：重复发布项
        postJson("/api/publications/publish-batch",
                        batchBody("req-" + p + "-b2",
                                batchItem(ch1, 1, 2), batchItem(ch1, 1, 2)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 窗口登记与既有占位按提交顺序裁决 ----------

    @Test
    void windowRegistrationConflictsWithExistingOccupancy() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        newAsset(p + "movieB", "SP-B");
        long grantB = newGrant(channel, p + "movieB");

        // 先发布 SP-B 素材占位
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieB", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 后登记 SP-A 窗口与既有占位冲突：409，返回冲突素材与交集
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_12))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].assetId").value(p + "movieB"))
                .andExpect(jsonPath("$.conflicts[0].assetSponsorKey").value("SP-B"))
                .andExpect(jsonPath("$.conflicts[0].conflictStart").value(T_10))
                .andExpect(jsonPath("$.conflicts[0].conflictEnd").value(T_11))
                .andExpect(jsonPath("$.conflicts[0].overlapMs").value(3600000));
        // 与占位同赞助商的窗口可登记
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w2", p + "w2", "SP-B", List.of(channel), T_10, T_12))
                .andExpect(status().isOk());
        // 不相交区间的 SP-A 窗口可登记
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w3", p + "w3", "SP-A", List.of(channel),
                                T_13, "2026-09-22T14:00:00.000+08:00"))
                .andExpect(status().isOk());

        // ACTIVE 插播同样构成占位：先插播 SP-B 素材，再登记 SP-A 窗口冲突
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov1", "overrideKey", p + "ov1",
                        "channelId", channel, "assetId", p + "movieB", "grantId", grantB,
                        "priority", 5, "start", T_12, "end", "2026-09-22T12:10:00.000+08:00"))
                .andExpect(status().isOk());
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w4", p + "w4", "SP-A", List.of(channel),
                                "2026-09-22T12:05:00.000+08:00", T_13))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].assetId").value(p + "movieB"));
    }

    // ---------- 紧急插播排他校验 ----------

    @Test
    void emergencyOverrideValidatesExclusivity() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        newAsset(p + "movieB", "SP-B");
        long grantA = newGrant(channel, p + "movieA");
        long grantB = newGrant(channel, p + "movieB");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_12))
                .andExpect(status().isOk());

        // 409：窗口内插播异赞助素材
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov1", "overrideKey", p + "ov1",
                        "channelId", channel, "assetId", p + "movieB", "grantId", grantB,
                        "priority", 5, "start", T_10, "end", "2026-09-22T10:10:00.000+08:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].windowKey").value(p + "w1"))
                .andExpect(jsonPath("$.conflicts[0].overlapMs").value(600000));
        // 同赞助商素材可插播
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov2", "overrideKey", p + "ov2",
                        "channelId", channel, "assetId", p + "movieA", "grantId", grantA,
                        "priority", 5, "start", T_10, "end", "2026-09-22T10:10:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 窗口外插播异赞助素材合法
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov3", "overrideKey", p + "ov3",
                        "channelId", channel, "assetId", p + "movieB", "grantId", grantB,
                        "priority", 5, "start", T_12, "end", "2026-09-22T12:10:00.000+08:00"))
                .andExpect(status().isOk());
        // 删除窗口后，原冲突插播放行
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", "req-" + p + "-dw"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "-ov4", "overrideKey", p + "ov4",
                        "channelId", channel, "assetId", p + "movieB", "grantId", grantB,
                        "priority", 6, "start", T_10, "end", "2026-09-22T10:10:00.000+08:00"))
                .andExpect(status().isOk());
    }
    // ---------- 发布撤销 ----------

    @Test
    void revokeFuturePublicationReleasesOccupancyAndKeepsHistory() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        newAsset(p + "movieB", "SP-B");
        LocalDate futureDay = LocalDate.now(ZONE).plusDays(2);
        String day = futureDay.toString();
        String segStart = fmt(futureDay.atTime(10, 0));
        String segEnd = fmt(futureDay.atTime(11, 0));
        String grantFrom = fmt(futureDay.atStartOfDay());
        String grantTo = fmt(futureDay.plusDays(1).atStartOfDay());
        newGrant(channel, p + "movieA", grantFrom, grantTo);
        newGrant(channel, p + "movieB", grantFrom, grantTo);
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel),
                                day, segStart, segEnd))
                .andExpect(status().isOk());

        putJson("/api/channels/" + channel + "/drafts/" + day,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieA",
                                segStart, segEnd)))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + day + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 撤销尚未开始的发布：释放占位，保留窗口命中历史
        postJson("/api/publications/" + publicationId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.revoked").value(true));
        // 幂等重放：同 requestId 返回首次结果
        postJson("/api/publications/" + publicationId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        // 409：新 requestId 重复撤销
        postJson("/api/publications/" + publicationId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLICATION_ALREADY_REVOKED"));
        // 播出决定回退保底（撤销后不再生效）
        getJson("/api/channels/" + channel + "/playout", fmt(futureDay.atTime(10, 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        // 窗口命中历史保留在快照中
        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.segments[0].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowKey").value(p + "w1"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowVersion").value(1));
        // 占位已释放：异赞助窗口可登记同一区间
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w2", p + "w2", "SP-B", List.of(channel),
                                day, segStart, segEnd))
                .andExpect(status().isOk());
        // 404：撤销不存在的发布
        postJson("/api/publications/999999/revoke", Map.of("requestId", "req-" + p + "-rv9"))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokeStartedPublicationRejected() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        LocalDate today = LocalDate.now(ZONE);
        String day = today.toString();
        newGrant(channel, p + "movieA", fmt(today.atStartOfDay()),
                fmt(today.plusDays(1).atStartOfDay()));
        // 片段自当日 00:00 开始：撤销时已开始播出
        putJson("/api/channels/" + channel + "/drafts/" + day,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieA",
                                fmt(today.atStartOfDay()), fmt(today.atTime(23, 59)))))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + day + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 409：已开始的播出只能按既有紧急流程处理
        postJson("/api/publications/" + publicationId + "/revoke",
                        Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLICATION_ALREADY_STARTED"));
        // 发布仍然生效
        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(false));
    }

    // ---------- 快照固化：窗口后续修订/删除不改写已发布快照 ----------

    @Test
    void snapshotStableAfterWindowReviseAndDelete() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        newGrant(channel, p + "movieA");
        postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-w1", p + "w1", "SP-A", List.of(channel), T_10, T_12))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieA", T_10, T_11)))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 修订窗口（版本 2）再删除：已发布快照不改写
        putJson("/api/sponsor-windows/" + p + "w1",
                        reviseBody("req-" + p + "-rv1", 1, List.of(channel), T_10, T_13))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", "req-" + p + "-dw"))
                .andExpect(status().isOk());

        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowKey").value(p + "w1"))
                .andExpect(jsonPath("$.segments[0].sponsorWindowVersion").value(1));
        // 播出决定仍按快照生效
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movieA"));
    }

    // ---------- 并发：同频道同时段不得出现两个异赞助生效素材 ----------

    @Test
    void concurrentWindowRegistrationAdjudicatedByCommitOrder() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        List<Integer> statuses = runConcurrently(2, i -> postJson("/api/sponsor-windows",
                        windowBody("req-" + p + "-cw" + i, p + "cw" + i, "SP-A", List.of(channel),
                                T_10, T_12))
                .andReturn().getResponse().getStatus());
        // 同赞助商同频道重叠窗口按提交顺序裁决：恰一个成功，另一个 422
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        getJson("/api/sponsor-windows?sponsorKey=SP-A&businessDay=" + DAY + "&channelId=" + channel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void concurrentBatchPublishSameRequestIdReplaysSingleResult() throws Exception {
        String p = prefix();
        String channel = newChannel(p, "ch");
        newAsset(p + "movieA", "SP-A");
        newGrant(channel, p + "movieA");
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        draftBody("req-" + p + "-d1", 0, segment(p + "s1", p + "movieA", T_10, T_11)))
                .andExpect(status().isOk());
        Map<String, Object> body = batchBody("req-" + p + "-b1", batchItem(channel, 1, 0));
        List<String> responses = runConcurrentlyForBody(2, i -> postJson(
                "/api/publications/publish-batch", body).andReturn().getResponse().getContentAsString());
        // 同 requestId 同参数并发：都返回首次结果，只发布一次
        assertThat(responses.get(0)).isEqualTo(responses.get(1));
        assertThat(readJson(responses.get(0)).get("items").get(0).get("publishedVersion").asLong())
                .isEqualTo(1);
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"));
    }
    // ---------- 测试辅助 ----------

    /** 创建频道（含保底素材），返回频道 ID。 */
    private String newChannel(String p, String suffix) throws Exception {
        postJson("/api/assets", Map.of("id", p + suffix + "-fb", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + suffix, "fallbackAssetId", p + suffix + "-fb"))
                .andExpect(status().isOk());
        return p + suffix;
    }

    private void newAsset(String id, String sponsorKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("durationMs", 3600000);
        if (sponsorKey != null) {
            body.put("sponsorKey", sponsorKey);
        }
        postJson("/api/assets", body).andExpect(status().isOk());
    }

    private long newGrant(String channel, String asset) throws Exception {
        return newGrant(channel, asset, GRANT_FROM, GRANT_TO);
    }

    private long newGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
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

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 Map<String, Object> segment) {
        return draftBody(requestId, expectedVersion, List.of(segment));
    }

    private static Map<String, Object> draftBody(String requestId, long expectedVersion,
                                                 List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        return body;
    }

    private static Map<String, Object> windowBody(String requestId, String windowKey,
                                                  String sponsorKey, List<String> channelIds,
                                                  String start, String end) {
        return windowBody(requestId, windowKey, sponsorKey, channelIds, DAY, start, end);
    }

    private static Map<String, Object> windowBody(String requestId, String windowKey,
                                                  String sponsorKey, List<String> channelIds,
                                                  String businessDay, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("windowKey", windowKey);
        body.put("sponsorKey", sponsorKey);
        body.put("businessDay", businessDay);
        body.put("channelIds", channelIds);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> reviseBody(String requestId, long expectedVersion,
                                                  List<String> channelIds, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("channelIds", channelIds);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> batchItem(String channelId, long draftVersion,
                                                 long expectedPublishedVersion) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("channelId", channelId);
        item.put("businessDay", DAY);
        item.put("draftVersion", draftVersion);
        item.put("expectedPublishedVersion", expectedPublishedVersion);
        return item;
    }

    private static Map<String, Object> batchBody(String requestId, Map<String, Object>... items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", List.of(items));
        return body;
    }

    private static String fmt(java.time.LocalDateTime time) {
        return JacksonConfig.OUTPUT_FORMAT.format(time.atZone(ZONE));
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

    private ResultActions getJson(String url, String at) throws Exception {
        return mvc.perform(get(url).param("at", at));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readJson(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private static String prefix() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingCall<T> {
        T get(int index) throws Exception;
    }

    private static List<Integer> runConcurrently(int threads, ThrowingCall<Integer> action)
            throws Exception {
        return runConcurrentlyGeneric(threads, action);
    }

    private static List<String> runConcurrentlyForBody(int threads, ThrowingCall<String> action)
            throws Exception {
        return runConcurrentlyGeneric(threads, action);
    }

    private static <T> List<T> runConcurrentlyGeneric(int threads, ThrowingCall<T> action)
            throws Exception {
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