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
 * 内容分级时段管控测试：分级声明、管控时段配置与重叠校验、发布越权拦截、
 * 紧急插播分级限制、历史校验记录查询、播出决定分级返回及并发一致判定。
 * 运行环境为 H2（MySQL 兼容模式）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutRatingApiTest {

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

    // ---------- 素材分级声明 ----------

    @Test
    void assetRatingDeclaredOrDefaulted() throws Exception {
        String p = prefix();

        // 声明分级
        postJson("/api/assets", Map.of("id", p + "g", "durationMs", 1000, "rating", "G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("G"));
        postJson("/api/assets", Map.of("id", p + "m", "durationMs", 1000, "rating", "MATURE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("MATURE"));
        // 不声明分级：rating 为 null，校验时按 MATURE
        postJson("/api/assets", Map.of("id", p + "legacy", "durationMs", 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").doesNotExist());
        // 400：非法分级值
        mvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("id", p + "bad", "durationMs", 1000, "rating", "X18"))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 管控时段配置 ----------

    @Test
    void ratingWindowCrudOverlapAndIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        // 创建主流程：10:00-11:00 仅允许 G
        MvcResult created = postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMinute").value(600))
                .andExpect(jsonPath("$.endMinute").value(660))
                .andExpect(jsonPath("$.maxRating").value("G"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        long windowId = readJson(created).get("id").asLong();

        // 幂等重放：同 requestId 同参数返回首次结果
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(windowId));
        // 同 requestId 异参数：409
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "PG"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 422：与既有时段重叠（部分相交）
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w2", 630, 720, "PG"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_WINDOW_OVERLAP"));
        // 422：包含关系也算重叠
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w3", 0, 1440, "MATURE"))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占键：同 requestId 修正参数后成功（端点相接合法）
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w2", 660, 720, "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startMinute").value(660));

        // 400：终点不大于起点 / 超出运营日范围
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w4", 660, 660, "G"))
                .andExpect(status().isBadRequest());
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w5", 0, 1441, "G"))
                .andExpect(status().isBadRequest());
        // 404：频道不存在
        postJson("/api/channels/" + p + "ghost/rating-windows",
                        windowBody("req-" + p + "-w6", 0, 60, "G"))
                .andExpect(status().isNotFound());

        // 修改主流程：版本 1 → 2，缩为仅 10:00-10:30
        putJson("/api/channels/" + channel + "/rating-windows/" + windowId + "?expectedVersion=1",
                        windowBody("req-" + p + "-u1", 600, 630, "G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.endMinute").value(630));
        // 409：版本不符
        putJson("/api/channels/" + channel + "/rating-windows/" + windowId + "?expectedVersion=1",
                        windowBody("req-" + p + "-u2", 600, 630, "PG"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RATING_WINDOW_VERSION_CONFLICT"));
        // 422：修改后与其他时段重叠
        putJson("/api/channels/" + channel + "/rating-windows/" + windowId + "?expectedVersion=2",
                        windowBody("req-" + p + "-u3", 600, 720, "G"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_WINDOW_OVERLAP"));
        // 404：时段不存在
        putJson("/api/channels/" + channel + "/rating-windows/999999?expectedVersion=1",
                        windowBody("req-" + p + "-u4", 0, 60, "G"))
                .andExpect(status().isNotFound());

        // 查询配置：两个生效时段
        mvc.perform(get("/api/channels/" + channel + "/rating-windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].startMinute").value(600))
                .andExpect(jsonPath("$[1].startMinute").value(660));
        // 404：查询不存在频道的配置
        mvc.perform(get("/api/channels/" + p + "ghost/rating-windows"))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布分级校验 ----------

    @Test
    void publishBlockedByRatingWindowAndListsAllViolations() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 草稿：10:00-11:00 MATURE 电影 + 11:00-12:00 未声明分级的历史素材
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0, List.of(
                                segment(p + "seg-1", p + "mature", T_10, T_11),
                                segment(p + "seg-2", p + "legacy", T_11, T_12))))
                .andExpect(status().isOk());

        // 管控时段：10:00-12:00 仅允许 PG
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 720, "PG"))
                .andExpect(status().isOk());

        // 发布：两条素材均越级（MATURE > PG；未声明按 MATURE > PG），422 且列出全部明细
        MvcResult rejected = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.violations[0].assetRating").value("MATURE"))
                .andExpect(jsonPath("$.violations[0].allowedRating").value("PG"))
                .andExpect(jsonPath("$.violations[0].windowStartMinute").value(600))
                .andExpect(jsonPath("$.violations[1].segmentId").value(p + "seg-2"))
                .andExpect(jsonPath("$.violations[1].assetRating").value("MATURE"))
                .andReturn();
        assertThat(readJson(rejected).get("violations").get(0).get("windowId").asLong()).isPositive();

        // 未发布：播出查询仍为无已发布编排
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));
        // 失败不占键：同 requestId 改参数不冲突（按异参 409 之前提是首次已成功，这里首次失败）
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity());

        // 放宽时段为 MATURE 后发布成功，并写入历史校验记录
        MvcResult windows = mvc.perform(get("/api/channels/" + channel + "/rating-windows"))
                .andReturn();
        long windowId = readJson(windows).get(0).get("id").asLong();
        putJson("/api/channels/" + channel + "/rating-windows/" + windowId + "?expectedVersion=1",
                        windowBody("req-" + p + "-u1", 600, 720, "MATURE"))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 历史校验记录：两条，均命中该时段
        mvc.perform(get("/api/channels/" + channel + "/rating-checks")
                        .param("businessDay", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].publishedVersion").value(1))
                .andExpect(jsonPath("$[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$[0].assetRating").value("MATURE"))
                .andExpect(jsonPath("$[0].windowId").value(windowId))
                .andExpect(jsonPath("$[0].allowedRating").value("MATURE"))
                .andExpect(jsonPath("$[1].segmentId").value(p + "seg-2"))
                .andExpect(jsonPath("$[1].assetRating").value("MATURE"));
    }

    @Test
    void publishOutsideWindowUnrestrictedAndBoundaryMinutes() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 管控时段仅覆盖 10:00-11:00（含起点不含终点）
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "G"))
                .andExpect(status().isOk());

        // 片段 11:00-12:00 完全在时段外：MATURE 素材不受限，发布成功
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "mature", T_11, T_12)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 校验记录：未命中时段，windowId 为空表示无限制
        mvc.perform(get("/api/channels/" + channel + "/rating-checks")
                        .param("businessDay", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].windowId").doesNotExist())
                .andExpect(jsonPath("$[0].allowedRating").doesNotExist());

        // 边界：片段起点等于时段终点（11:00）不命中时段 —— 上面已验证；
        // 片段起点等于时段起点（10:00）命中时段：G 素材允许，PG 素材越级
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d2", 1,
                                segment(p + "seg-2", p + "pg", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 2,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations[0].assetRating").value("PG"))
                .andExpect(jsonPath("$.violations[0].allowedRating").value("G"));
    }

    @Test
    void windowChangeDoesNotRewritePublishedSnapshot() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 无管控时段时发布 MATURE 片段成功
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "mature", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 之后新增管控时段：已发布快照不被追溯改写，播出查询仍返回节目素材
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "G"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "mature"))
                .andExpect(jsonPath("$.assetRating").value("MATURE"))
                .andExpect(jsonPath("$.windowMaxRating").value("G"));

        // 历史校验记录仍记录发布时无时段命中的状态
        mvc.perform(get("/api/channels/" + channel + "/rating-checks")
                        .param("businessDay", DAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].windowId").doesNotExist());

        // 但新发布受新配置约束：同草稿再发布被 422 拦截
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"));
    }

    // ---------- 紧急插播 ----------

    @Test
    void interruptionRatingEnforcedAndIdempotent() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 管控时段：10:00-11:00 仅允许 G
        MvcResult window = postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "G"))
                .andExpect(status().isOk())
                .andReturn();
        long windowId = readJson(window).get("id").asLong();

        // G 素材在时段内插播成功，返回命中时段
        MvcResult ok = postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i1", p + "g",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetRating").value("G"))
                .andExpect(jsonPath("$.windowId").value(windowId))
                .andReturn();
        long interruptionId = readJson(ok).get("id").asLong();

        // 幂等重放：同 requestId 同参数返回首次结果
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i1", p + "g",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(interruptionId));
        // 同 requestId 异参数：409
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i1", p + "g",
                                "2026-09-22T10:31:00.000+08:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // PG 素材在 G 时段内插播：422，不创建插播
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i2", p + "pg",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations[0].assetRating").value("PG"))
                .andExpect(jsonPath("$.violations[0].allowedRating").value("G"));
        // 失败不占键：同 requestId 换到时段外时刻成功
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i2", p + "pg",
                                "2026-09-22T12:00:00.000+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetRating").value("PG"))
                .andExpect(jsonPath("$.windowId").doesNotExist());

        // 未声明分级的历史素材按 MATURE 参与校验：G 时段内 422
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i3", p + "legacy",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].assetRating").value("MATURE"));

        // 404：素材不存在 / 频道不存在
        postJson("/api/channels/" + channel + "/interruptions",
                        interruptionBody("req-" + p + "-i4", p + "ghost",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isNotFound());
        postJson("/api/channels/" + p + "ghost/interruptions",
                        interruptionBody("req-" + p + "-i5", p + "g",
                                "2026-09-22T10:30:00.000+08:00"))
                .andExpect(status().isNotFound());
    }

    // ---------- 播出决定返回分级与命中时段 ----------

    @Test
    void playoutDecisionReturnsRatingAndMatchedWindow() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", 600, 660, "MATURE"))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "pg", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 时段内命中节目：返回素材分级与命中时段
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetRating").value("PG"))
                .andExpect(jsonPath("$.windowStartMinute").value(600))
                .andExpect(jsonPath("$.windowEndMinute").value(660))
                .andExpect(jsonPath("$.windowMaxRating").value("MATURE"));
        // 时段外空档保底：返回保底素材分级，无命中时段
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T15:00:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.assetRating").value("G"))
                .andExpect(jsonPath("$.windowId").doesNotExist());
    }

    // ---------- 并发：时段变更与发布按提交顺序裁决 ----------

    @Test
    void concurrentWindowCreateAndPublishConsistentOutcome() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "mature", T_10, T_11)))
                .andExpect(status().isOk());

        // 并发：创建仅允许 G 的管控时段 vs 发布含 MATURE 片段的草稿
        List<Integer> results = runConcurrently(List.of(
                () -> mvc.perform(post("/api/channels/" + channel + "/rating-windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(windowBody("req-" + p + "-w1", 600, 660, "G"))))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/channels/" + channel + "/drafts/" + DAY + "/publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + p + "-p1",
                                        "draftVersion", 1, "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus()));

        int windowStatus = results.get(0);
        int publishStatus = results.get(1);
        assertThat(windowStatus).isEqualTo(200);
        // 发布要么在时段生效前提交（200），要么读到已生效时段被拦截（422），不允许中间态
        assertThat(publishStatus).isIn(200, 422);

        // 最终状态一致：若发布成功，校验记录反映发布提交时刻的一致配置
        MvcResult checks = mvc.perform(get("/api/channels/" + channel + "/rating-checks")
                        .param("businessDay", DAY))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode records = readJson(checks);
        if (publishStatus == 200) {
            assertThat(records.size()).isEqualTo(1);
            // 判定基于单一一致配置：要么命中时段要么不命中，不允许混合
            if (records.get(0).has("windowId")) {
                assertThat(records.get(0).get("allowedRating").asText()).isEqualTo("G");
            }
        } else {
            assertThat(records.size()).isZero();
        }
    }

    @Test
    void concurrentWindowCreateAndInterruptionConsistentOutcome() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 并发：创建仅允许 G 的时段（覆盖 10:30）vs MATURE 素材 10:30 插播
        List<Integer> results = runConcurrently(List.of(
                () -> mvc.perform(post("/api/channels/" + channel + "/rating-windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(windowBody("req-" + p + "-w1", 600, 660, "G"))))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(post("/api/channels/" + channel + "/interruptions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(interruptionBody("req-" + p + "-i1", p + "mature",
                                        "2026-09-22T10:30:00.000+08:00"))))
                        .andReturn().getResponse().getStatus()));

        assertThat(results.get(0)).isEqualTo(200);
        // 插播要么先于时段生效（200），要么被已生效时段拦截（422）
        assertThat(results.get(1)).isIn(200, 422);
    }

    // ---------- 测试辅助 ----------

    /** 创建保底素材与频道，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000, "rating", "G"))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        return p + "ch";
    }

    /** 创建频道及 G/PG/MATURE/未声明分级素材，并为全部素材授予全天授权。 */
    private String newChannelWithRatedAssets(String p) throws Exception {
        String channel = newChannel(p);
        postJson("/api/assets", Map.of("id", p + "g", "durationMs", 1000, "rating", "G"))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "pg", "durationMs", 1000, "rating", "PG"))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "mature", "durationMs", 1000, "rating", "MATURE"))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "legacy", "durationMs", 1000))
                .andExpect(status().isOk());
        for (String asset : List.of(p + "g", p + "pg", p + "mature", p + "legacy")) {
            postJson("/api/grants", Map.of(
                            "channelId", channel, "assetId", asset,
                            "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                    .andExpect(status().isOk());
        }
        return channel;
    }

    private static Map<String, Object> windowBody(String requestId, int startMinute,
                                                  int endMinute, String maxRating) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("startMinute", startMinute);
        body.put("endMinute", endMinute);
        body.put("maxRating", maxRating);
        return body;
    }

    private static Map<String, Object> interruptionBody(String requestId, String assetId,
                                                        String at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("assetId", assetId);
        body.put("at", at);
        return body;
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
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
    }

    private interface ThrowingAction {
        Integer run() throws Exception;
    }

    private static List<Integer> runConcurrently(List<ThrowingAction> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (ThrowingAction action : actions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return action.run();
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
