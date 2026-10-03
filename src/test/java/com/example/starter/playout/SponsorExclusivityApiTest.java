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

import java.time.LocalDate;
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
 * 赞助排他 API 端到端测试：窗口登记/修订/删除、发布与紧急插播排他校验、跨频道批量发布事务、
 * 撤销发布、快照固化、幂等与并发边界。运行环境为 H2（MODE=MySQL）内存库，
 * 唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SponsorExclusivityApiTest {

    private static final String DAY = "2026-09-22";
    private static final String FUTURE_DAY = "2099-01-01";
    private static final String PAST_DAY = "2020-01-01";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 窗口登记：主流程与 422 校验分支 ----------

    @Test
    void windowRegistrationMainFlowAndValidation() throws Exception {
        String p = prefix();
        String ch1 = createChannelWithFallback(p, "ch1");
        String ch2 = createChannelWithFallback(p, "ch2");

        // 主流程：多频道范围，响应频道按字典序排序，时间毫秒精度，版本 1
        postJson("/api/sponsor-windows", windowBody(p + "r1", p + "w1", "SP-A", DAY,
                        List.of(ch2, ch1), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowKey").value(p + "w1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.channels[0]").value(ch1))
                .andExpect(jsonPath("$.channels[1]").value(ch2))
                .andExpect(jsonPath("$.start").value(t(DAY, "10:00")))
                .andExpect(jsonPath("$.end").value(t(DAY, "12:00")))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 422：频道集合为空
        postJson("/api/sponsor-windows", windowBody(p + "r2", p + "w2", "SP-A", DAY,
                        List.of(), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_CHANNELS_EMPTY"));

        // 422：时间非法（终点不大于起点），消息返回实际时长与要求
        MvcResult invalid = postJson("/api/sponsor-windows", windowBody(p + "r3", p + "w3",
                        "SP-A", DAY, List.of(ch1), t(DAY, "12:00"), t(DAY, "12:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_TIME_INVALID"))
                .andReturn();
        assertThat(readJson(invalid).get("message").asText()).contains("实际时长 0 毫秒");

        // 422：窗口跨运营日
        postJson("/api/sponsor-windows", windowBody(p + "r4", p + "w4", "SP-A", DAY,
                        List.of(ch1), t(DAY, "23:00"), t(nextDay(DAY), "01:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_CROSSES_DAY"));

        // 404：频道不存在
        postJson("/api/sponsor-windows", windowBody(p + "r5", p + "w5", "SP-A", DAY,
                        List.of(p + "ghost"), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isNotFound());

        // 409：窗口键重复
        postJson("/api/sponsor-windows", windowBody(p + "r6", p + "w1", "SP-B", DAY,
                        List.of(ch1), t(DAY, "14:00"), t(DAY, "15:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_WINDOW_KEY"));
    }

    // ---------- 窗口重叠规则：左闭右开边界、修订与删除 ----------

    @Test
    void windowOverlapReviseAndDeleteRules() throws Exception {
        String p = prefix();
        String ch1 = createChannelWithFallback(p, "ch1");
        String ch2 = createChannelWithFallback(p, "ch2");

        postJson("/api/sponsor-windows", windowBody(p + "r1", p + "w1", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk());

        // 422：同赞助商同频道重叠
        postJson("/api/sponsor-windows", windowBody(p + "r2", p + "w2", "SP-A", DAY,
                        List.of(ch1), t(DAY, "11:00"), t(DAY, "13:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));
        // 200：左闭右开，相邻不重叠
        postJson("/api/sponsor-windows", windowBody(p + "r3", p + "w3", "SP-A", DAY,
                        List.of(ch1), t(DAY, "12:00"), t(DAY, "14:00")))
                .andExpect(status().isOk());
        // 200：不同赞助商同频道同区间不冲突
        postJson("/api/sponsor-windows", windowBody(p + "r4", p + "w4", "SP-B", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk());
        // 200：同赞助商不同频道不冲突
        postJson("/api/sponsor-windows", windowBody(p + "r5", p + "w5", "SP-A", DAY,
                        List.of(ch2), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk());

        // 修订：版本递增，旧版本被取代；与自身旧版本重叠不算冲突
        postJson("/api/sponsor-windows/" + p + "w1/revise", reviseBody(p + "r6", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "11:30")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.end").value(t(DAY, "11:30")));
        // 修订后与 w3 [12:00,14:00) 重叠 → 422
        postJson("/api/sponsor-windows/" + p + "w1/revise", reviseBody(p + "r7", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "13:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));

        // 删除：ACTIVE → DELETED；重复删除 409；已删除不能修订
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", p + "r8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", p + "r9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WINDOW_NOT_ACTIVE"));
        postJson("/api/sponsor-windows/" + p + "w1/revise", reviseBody(p + "r10", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "11:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WINDOW_NOT_ACTIVE"));
        // 404：修订/删除不存在的窗口
        postJson("/api/sponsor-windows/" + p + "ghost/revise", reviseBody(p + "r11", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "11:00")))
                .andExpect(status().isNotFound());
        postJson("/api/sponsor-windows/" + p + "ghost/delete", Map.of("requestId", p + "r12"))
                .andExpect(status().isNotFound());
    }

    // ---------- 窗口登记幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void windowRegistrationIdempotency() throws Exception {
        String p = prefix();
        String ch1 = createChannelWithFallback(p, "ch1");
        postJson("/api/sponsor-windows", windowBody(p + "r0", p + "wx", "SP-A", DAY,
                        List.of(ch1), t(DAY, "08:00"), t(DAY, "09:00")))
                .andExpect(status().isOk());

        // 同 requestId 同参数重放 → 返回首次结果（同一 windowKey）
        Map<String, Object> body = windowBody(p + "r1", null, "SP-B", DAY,
                List.of(ch1), t(DAY, "10:00"), t(DAY, "11:00"));
        MvcResult first = postJson("/api/sponsor-windows", body)
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = postJson("/api/sponsor-windows", body)
                .andExpect(status().isOk()).andReturn();
        assertThat(readJson(replay).get("windowKey").asText())
                .isEqualTo(readJson(first).get("windowKey").asText());

        // 同 requestId 异参数 → 409
        postJson("/api/sponsor-windows", windowBody(p + "r1", null, "SP-B", DAY,
                        List.of(ch1), t(DAY, "12:00"), t(DAY, "13:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以重叠参数失败（422），再用同 requestId 合法参数成功
        postJson("/api/sponsor-windows", windowBody(p + "r2", p + "w2", "SP-A", DAY,
                        List.of(ch1), t(DAY, "08:30"), t(DAY, "09:30")))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/sponsor-windows", windowBody(p + "r2", p + "w2", "SP-A", DAY,
                        List.of(ch1), t(DAY, "09:00"), t(DAY, "10:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowKey").value(p + "w2"));
    }
    // ---------- 发布排他校验：409 明细、事务回滚、快照固化、旧证据不回写 ----------

    @Test
    void publishValidatesExclusivityAndSnapshotsWindowHits() throws Exception {
        String p = prefix();
        String ch = createChannelWithFallback(p, "ch");
        String aA1 = createAsset(p, "aA1", "SP-A");
        String aA2 = createAsset(p, "aA2", "SP-A");
        String aB = createAsset(p, "aB", "SP-B");
        String aN = createAsset(p, "aN", null);
        for (String asset : List.of(aA1, aA2, aB, aN)) {
            createGrant(ch, asset, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        }
        // 素材响应携带 sponsorKey；无赞助素材为 null
        getJson("/api/sponsor-windows", "channelId", ch, "businessDay", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        postJson("/api/sponsor-windows", windowBody(p + "rw", p + "w", "SP-A", DAY,
                        List.of(ch), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk());

        // 异赞助片段命中排他窗 → 整次 409，返回频道、时段、冲突素材
        replaceDraft(ch, DAY, 0, List.of(segment(p + "s1", aB, t(DAY, "10:00"), t(DAY, "11:00"))));
        MvcResult conflict = publish(ch, DAY, 1, 0, status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.details[0].channelId").value(ch))
                .andExpect(jsonPath("$.details[0].assetId").value(aB))
                .andExpect(jsonPath("$.details[0].assetSponsorKey").value("SP-B"))
                .andExpect(jsonPath("$.details[0].windowKey").value(p + "w"))
                .andExpect(jsonPath("$.details[0].windowSponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.details[0].start").value(t(DAY, "10:00")))
                .andExpect(jsonPath("$.details[0].end").value(t(DAY, "11:00")))
                .andReturn();
        assertThat(readJson(conflict).get("message").asText()).contains("1 处冲突");

        // 409 不留部分结果：发布版本仍为 0，播出决定回退保底
        getJson("/api/channels/" + ch + "/playout", "at", t(DAY, "10:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 修正草稿：同赞助商不同素材并存 + 无赞助素材 + 窗外异赞助，发布成功
        replaceDraft(ch, DAY, 1, List.of(
                segment(p + "s2", aA1, t(DAY, "10:00"), t(DAY, "10:30")),
                segment(p + "s3", aA2, t(DAY, "10:30"), t(DAY, "11:00")),
                segment(p + "s4", aN, t(DAY, "11:00"), t(DAY, "11:30")),
                segment(p + "s5", aB, t(DAY, "12:00"), t(DAY, "13:00"))));
        MvcResult published = publish(ch, DAY, 2, 0, status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 快照固化：片段 sponsorKey 与命中窗口版本
        MvcResult detail = getJson("/api/channels/" + ch + "/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.segments[0].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[1].sponsorKey").value("SP-A"))
                .andExpect(jsonPath("$.segments[3].sponsorKey").value("SP-B"))
                .andExpect(jsonPath("$.windowHits[0].windowKey").value(p + "w"))
                .andExpect(jsonPath("$.windowHits[0].windowVersion").value(1))
                .andExpect(jsonPath("$.windowHits[0].sponsorKey").value("SP-A"))
                .andReturn();
        assertThat(readJson(detail).at("/segments/2/sponsorKey").isNull()).isTrue();
        assertThat(readJson(detail).get("revokeRequestId").isNull()).isTrue();

        // 窗口修订并删除后，已发布快照的命中版本与 sponsorKey 不回写
        postJson("/api/sponsor-windows/" + p + "w/revise", reviseBody(p + "rv", "SP-A", DAY,
                        List.of(ch), t(DAY, "10:00"), t(DAY, "14:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/sponsor-windows/" + p + "w/delete", Map.of("requestId", p + "rd"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + ch + "/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowHits[0].windowVersion").value(1))
                .andExpect(jsonPath("$.segments[0].sponsorKey").value("SP-A"));

        // 播出决定命中已发布节目
        getJson("/api/channels/" + ch + "/playout", "at", t(DAY, "10:15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(aA1));
    }

    // ---------- 跨频道批量发布：完整校验、整次 409、事务原子性、幂等 ----------

    @Test
    void batchPublishAtomicAcrossChannels() throws Exception {
        String p = prefix();
        String ch1 = createChannelWithFallback(p, "ch1");
        String ch2 = createChannelWithFallback(p, "ch2");
        String aA1 = createAsset(p, "aA1", "SP-A");
        String aA2 = createAsset(p, "aA2", "SP-A");
        String aB = createAsset(p, "aB", "SP-B");
        for (String asset : List.of(aA1, aA2, aB)) {
            createGrant(ch1, asset, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
            createGrant(ch2, asset, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        }
        postJson("/api/sponsor-windows", windowBody(p + "rw", p + "w", "SP-A", DAY,
                        List.of(ch1, ch2), t(DAY, "10:00"), t(DAY, "12:00")))
                .andExpect(status().isOk());

        replaceDraft(ch1, DAY, 0, List.of(segment(p + "s1", aA1, t(DAY, "10:00"), t(DAY, "11:00"))));
        replaceDraft(ch2, DAY, 0, List.of(segment(p + "s2", aB, t(DAY, "10:00"), t(DAY, "11:00"))));

        // ch2 异赞助冲突 → 整次 409，ch1 不留部分更新
        postJson("/api/publications/batch", batchBody(p + "rb1",
                        batchItem(ch1, DAY, 1, 0), batchItem(ch2, DAY, 1, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.details[0].channelId").value(ch2))
                .andExpect(jsonPath("$.details[0].assetId").value(aB));
        getJson("/api/channels/" + ch1 + "/playout", "at", t(DAY, "10:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"));

        // 修正 ch2 后整批成功，顺序与请求一致，命中窗口历史按频道记录
        replaceDraft(ch2, DAY, 1, List.of(segment(p + "s3", aA2, t(DAY, "10:00"), t(DAY, "11:00"))));
        MvcResult batch = postJson("/api/publications/batch", batchBody(p + "rb2",
                        batchItem(ch1, DAY, 1, 0), batchItem(ch2, DAY, 2, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publications[0].channelId").value(ch1))
                .andExpect(jsonPath("$.publications[1].channelId").value(ch2))
                .andExpect(jsonPath("$.publications[0].publishedVersion").value(1))
                .andExpect(jsonPath("$.publications[1].publishedVersion").value(1))
                .andReturn();
        long pub1 = readJson(batch).at("/publications/0/publicationId").asLong();
        long pub2 = readJson(batch).at("/publications/1/publicationId").asLong();
        getJson("/api/channels/" + ch2 + "/publications/" + pub2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowHits[0].windowKey").value(p + "w"))
                .andExpect(jsonPath("$.windowHits[0].channelId").value(ch2));

        // 幂等：同 requestId 同参重放返回首次结果；异参 409
        postJson("/api/publications/batch", batchBody(p + "rb2",
                        batchItem(ch1, DAY, 1, 0), batchItem(ch2, DAY, 2, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publications[0].publicationId").value(pub1));
        postJson("/api/publications/batch", batchBody(p + "rb2",
                        batchItem(ch1, DAY, 1, 1), batchItem(ch2, DAY, 2, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 批量内重复频道+业务日 → 400
        postJson("/api/publications/batch", batchBody(p + "rb3",
                        batchItem(ch1, DAY, 1, 1), batchItem(ch1, DAY, 1, 1)))
                .andExpect(status().isBadRequest());
    }
    // ---------- 撤销发布：未开始可撤销、保留命中历史、已开始 409、幂等 ----------

    @Test
    void revokePublicationLifecycle() throws Exception {
        String p = prefix();
        String ch = createChannelWithFallback(p, "ch");
        String aA = createAsset(p, "aA", "SP-A");
        createGrant(ch, aA, t(FUTURE_DAY, "00:00"), t(nextDay(FUTURE_DAY), "00:00"));
        createGrant(ch, aA, t(PAST_DAY, "00:00"), t(nextDay(PAST_DAY), "00:00"));
        postJson("/api/sponsor-windows", windowBody(p + "rw", p + "w", "SP-A", FUTURE_DAY,
                        List.of(ch), t(FUTURE_DAY, "10:00"), t(FUTURE_DAY, "12:00")))
                .andExpect(status().isOk());

        // 尚未开始的发布可撤销：释放占位但保留窗口命中历史
        replaceDraft(ch, FUTURE_DAY, 0,
                List.of(segment(p + "s1", aA, t(FUTURE_DAY, "10:00"), t(FUTURE_DAY, "11:00"))));
        long publicationId = readJson(publish(ch, FUTURE_DAY, 1, 0, status().isOk()).andReturn())
                .get("publicationId").asLong();

        MvcResult revoked = postJson("/api/channels/" + ch + "/publications/" + publicationId
                        + "/revoke", Map.of("requestId", p + "rr1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value(p + "rr1"))
                .andExpect(jsonPath("$.windowHits[0].windowKey").value(p + "w"))
                .andExpect(jsonPath("$.windowHits[0].windowVersion").value(1))
                .andReturn();
        assertThat(readJson(revoked).get("revokedAt").isNull()).isFalse();

        // 撤销后占位释放：播出决定回退保底；命中历史仍可查
        getJson("/api/channels/" + ch + "/playout", "at", t(FUTURE_DAY, "10:30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        getJson("/api/channels/" + ch + "/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.windowHits[0].windowKey").value(p + "w"));

        // 幂等：同 requestId 重放首次结果；新 requestId 重复撤销 409
        postJson("/api/channels/" + ch + "/publications/" + publicationId + "/revoke",
                        Map.of("requestId", p + "rr1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        postJson("/api/channels/" + ch + "/publications/" + publicationId + "/revoke",
                        Map.of("requestId", p + "rr2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLICATION_ALREADY_REVOKED"));

        // 已经开始的发布只能按紧急流程处理：撤销 409
        replaceDraft(ch, PAST_DAY, 0,
                List.of(segment(p + "s2", aA, t(PAST_DAY, "10:00"), t(PAST_DAY, "11:00"))));
        long startedId = readJson(publish(ch, PAST_DAY, 1, 0, status().isOk()).andReturn())
                .get("publicationId").asLong();
        postJson("/api/channels/" + ch + "/publications/" + startedId + "/revoke",
                        Map.of("requestId", p + "rr3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLICATION_ALREADY_STARTED"));

        // 404：发布不存在 / 频道不匹配
        postJson("/api/channels/" + ch + "/publications/999999/revoke",
                        Map.of("requestId", p + "rr4"))
                .andExpect(status().isNotFound());
        getJson("/api/channels/" + p + "ghost/publications/" + publicationId)
                .andExpect(status().isNotFound());
    }

    // ---------- 紧急插播排他校验：异赞助 409、同赞助与无赞助放行、失败不占键 ----------

    @Test
    void emergencyOverrideRespectsExclusivityWindow() throws Exception {
        String p = prefix();
        String ch = createChannelWithFallback(p, "ch");
        String aA = createAsset(p, "aA", "SP-A");
        String aB = createAsset(p, "aB", "SP-B");
        String aN = createAsset(p, "aN", null);
        long gA = createGrant(ch, aA, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        long gB = createGrant(ch, aB, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        long gN = createGrant(ch, aN, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        postJson("/api/sponsor-windows", windowBody(p + "rw", p + "w", "SP-A", DAY,
                        List.of(ch), t(DAY, "10:00"), t(DAY, "11:00")))
                .andExpect(status().isOk());

        // 异赞助插播命中排他窗 → 409，明细含冲突素材
        postJson("/api/emergency-overrides", overrideBody(p + "ro1", p + "o1", ch, aB, gB,
                        t(DAY, "10:10"), t(DAY, "10:20")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SPONSOR_EXCLUSIVITY_CONFLICT"))
                .andExpect(jsonPath("$.details[0].assetId").value(aB))
                .andExpect(jsonPath("$.details[0].windowKey").value(p + "w"));

        // 失败不占键：同 requestId 换同赞助素材成功
        postJson("/api/emergency-overrides", overrideBody(p + "ro1", p + "o1", ch, aA, gA,
                        t(DAY, "10:10"), t(DAY, "10:20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 无赞助素材可插播
        postJson("/api/emergency-overrides", overrideBody(p + "ro2", p + "o2", ch, aN, gN,
                        t(DAY, "10:20"), t(DAY, "10:30")))
                .andExpect(status().isOk());

        // 播出决定命中插播
        getJson("/api/channels/" + ch + "/playout", "at", t(DAY, "10:15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(aA));
    }

    // ---------- 并发：窗口登记串行裁决、同频道发布只成功一次 ----------

    @Test
    void concurrentWindowRegistrationAndPublishSerialization() throws Exception {
        String p = prefix();
        String ch = createChannelWithFallback(p, "ch");

        // 8 个并发同赞助商同频道重叠窗口登记：按提交顺序裁决，恰好一个成功
        List<Integer> statuses = runConcurrently(8, i -> {
            Map<String, Object> body = windowBody(p + "rc" + i, p + "wc" + i, "SP-A", DAY,
                    List.of(ch), t(DAY, "10:00"), t(DAY, "11:00"));
            return mvc.perform(post("/api/sponsor-windows")
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(7);
        // 最终数据：恰好一个 ACTIVE 窗口
        getJson("/api/sponsor-windows", "channelId", ch, "businessDay", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 两个并发发布同一频道业务日：恰好一个成功，最终发布版本为 1
        String aA = createAsset(p, "aA", "SP-A");
        createGrant(ch, aA, t(DAY, "00:00"), t(nextDay(DAY), "00:00"));
        replaceDraft(ch, DAY, 0, List.of(segment(p + "s1", aA, t(DAY, "10:00"), t(DAY, "11:00"))));
        List<Integer> publishStatuses = runConcurrently(2, i -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestId", p + "rp" + i);
            body.put("draftVersion", 1);
            body.put("expectedPublishedVersion", 0);
            return mvc.perform(post("/api/channels/" + ch + "/drafts/" + DAY + "/publish")
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(publishStatuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(publishStatuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
        // 最终数据核对：版本 0 的发布请求已被拒绝（当前版本为 1）
        Map<String, Object> again = new LinkedHashMap<>();
        again.put("requestId", p + "rp-again");
        again.put("draftVersion", 1);
        again.put("expectedPublishedVersion", 0);
        postJson("/api/channels/" + ch + "/drafts/" + DAY + "/publish", again)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));
    }

    // ---------- 查询：列表排序稳定、只读、删除后明细可查 ----------

    @Test
    void windowQueriesAreStableAndReadOnly() throws Exception {
        String p = prefix();
        String ch1 = createChannelWithFallback(p, "ch1");
        String ch2 = createChannelWithFallback(p, "ch2");
        postJson("/api/sponsor-windows", windowBody(p + "r1", p + "w2", "SP-B", DAY,
                        List.of(ch1), t(DAY, "14:00"), t(DAY, "15:00")))
                .andExpect(status().isOk());
        postJson("/api/sponsor-windows", windowBody(p + "r2", p + "w1", "SP-A", DAY,
                        List.of(ch1), t(DAY, "10:00"), t(DAY, "11:00")))
                .andExpect(status().isOk());
        postJson("/api/sponsor-windows", windowBody(p + "r3", p + "w3", "SP-A", DAY,
                        List.of(ch2), t(DAY, "10:00"), t(DAY, "11:00")))
                .andExpect(status().isOk());

        // 列表按开始时间排序，只含本频道 ACTIVE 窗口
        getJson("/api/sponsor-windows", "channelId", ch1, "businessDay", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].windowKey").value(p + "w1"))
                .andExpect(jsonPath("$[1].windowKey").value(p + "w2"));

        // 删除后列表收缩，明细仍可查且状态为 DELETED；重复查询不改变状态
        postJson("/api/sponsor-windows/" + p + "w1/delete", Map.of("requestId", p + "r4"))
                .andExpect(status().isOk());
        getJson("/api/sponsor-windows/" + p + "w1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.channels[0]").value(ch1));
        for (int i = 0; i < 2; i++) {
            getJson("/api/sponsor-windows", "channelId", ch1, "businessDay", DAY)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].windowKey").value(p + "w2"));
        }
        getJson("/api/sponsor-windows/" + p + "w1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));

        // 404：窗口键不存在；列表频道不存在
        getJson("/api/sponsor-windows/" + p + "ghost").andExpect(status().isNotFound());
        getJson("/api/sponsor-windows", "channelId", p + "ghost", "businessDay", DAY)
                .andExpect(status().isNotFound());
    }
    // ---------- 辅助方法 ----------

    private static String t(String day, String hhmm) {
        return day + "T" + hhmm + ":00.000+08:00";
    }

    private static String nextDay(String day) {
        return LocalDate.parse(day).plusDays(1).toString();
    }

    private String createAsset(String p, String suffix, String sponsorKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p + suffix);
        if (sponsorKey != null) {
            body.put("sponsorKey", sponsorKey);
        }
        body.put("durationMs", 60000);
        postJson("/api/assets", body).andExpect(status().isOk());
        return p + suffix;
    }

    private String createChannelWithFallback(String p, String suffix) throws Exception {
        String fallback = createAsset(p, "fb-" + suffix, null);
        postJson("/api/channels", Map.of("id", p + suffix, "fallbackAssetId", fallback))
                .andExpect(status().isOk());
        return p + suffix;
    }

    private long createGrant(String channelId, String assetId, String from, String to)
            throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channelId, "assetId", assetId,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private void replaceDraft(String channelId, String day, long expectedVersion,
                              List<Map<String, Object>> segments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "rd-" + UUID.randomUUID());
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        mvc.perform(put("/api/channels/" + channelId + "/drafts/" + day)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk());
    }

    private ResultActions publish(String channelId, String day, long draftVersion,
                                  long expectedPublished,
                                  org.springframework.test.web.servlet.ResultMatcher matcher)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "rp-" + UUID.randomUUID());
        body.put("draftVersion", draftVersion);
        body.put("expectedPublishedVersion", expectedPublished);
        return postJson("/api/channels/" + channelId + "/drafts/" + day + "/publish", body)
                .andExpect(matcher);
    }

    private static Map<String, Object> segment(String id, String assetId,
                                               String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> windowBody(String requestId, String windowKey,
                                                  String sponsorKey, String day,
                                                  List<String> channels, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        if (windowKey != null) {
            body.put("windowKey", windowKey);
        }
        body.put("sponsorKey", sponsorKey);
        body.put("businessDay", day);
        body.put("channels", channels);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> reviseBody(String requestId, String sponsorKey, String day,
                                                  List<String> channels, String start, String end) {
        return windowBody(requestId, null, sponsorKey, day, channels, start, end);
    }

    private static Map<String, Object> overrideBody(String requestId, String overrideKey,
                                                    String channelId, String assetId, long grantId,
                                                    String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", overrideKey);
        body.put("channelId", channelId);
        body.put("assetId", assetId);
        body.put("grantId", grantId);
        body.put("priority", 5);
        body.put("start", start);
        body.put("end", end);
        return body;
    }

    private static Map<String, Object> batchItem(String channelId, String day, long draftVersion,
                                                 long expectedPublished) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("channelId", channelId);
        item.put("businessDay", day);
        item.put("draftVersion", draftVersion);
        item.put("expectedPublishedVersion", expectedPublished);
        return item;
    }

    private static Map<String, Object> batchBody(String requestId,
                                                 Map<String, Object>... items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", List.of(items));
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private ResultActions getJson(String url, String param, String value) throws Exception {
        return mvc.perform(get(url).param(param, value));
    }

    private ResultActions getJson(String url, String param1, String value1,
                                  String param2, String value2) throws Exception {
        return mvc.perform(get(url).param(param1, value1).param(param2, value2));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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