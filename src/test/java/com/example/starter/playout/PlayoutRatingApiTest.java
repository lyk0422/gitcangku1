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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容分级管控测试：素材分级登记、管控时段配置、发布分级校验与越权拦截、
 * 紧急插播分级门禁、播出查询分级/时段返回、幂等与并发一致性。
 * 运行环境为 H2（MySQL 兼容模式）内存库，表结构与生产 MySQL DDL 一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayoutRatingApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_06 = "2026-09-22T06:00:00.000+08:00";
    private static final String T_08 = "2026-09-22T08:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_12 = "2026-09-22T12:00:00.000+08:00";
    private static final String T_13 = "2026-09-22T13:00:00.000+08:00";
    private static final String T_14 = "2026-09-22T14:00:00.000+08:00";
    private static final String T_18 = "2026-09-22T18:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 素材分级登记 ----------

    @Test
    void assetRatingRegistration() throws Exception {
        String p = prefix();

        // 声明分级主流程
        postJson("/api/assets", Map.of("id", p + "pg", "durationMs", 1000, "rating", "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("PG"));
        // 不声明分级：响应 rating 为 null，校验时按 MATURE 处理
        postJson("/api/assets", Map.of("id", p + "none", "durationMs", 1000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(nullValue()));
        // 400：非法分级取值
        mvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("id", p + "bad", "durationMs", 1000, "rating", "XXX"))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 管控时段配置 ----------

    @Test
    void ratingWindowCrudValidationAndIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 404：频道不存在
        postJson("/api/channels/" + p + "ghost/rating-windows",
                        windowBody("req-" + p + "-w0", DAY, T_08, T_12, "PG"))
                .andExpect(status().isNotFound());

        // 创建主流程 [08,12) PG
        MvcResult w1 = postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", DAY, T_08, T_12, "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.businessDay").value(DAY))
                .andExpect(jsonPath("$.start").value(T_08))
                .andExpect(jsonPath("$.end").value(T_12))
                .andExpect(jsonPath("$.maxRating").value("PG"))
                .andReturn();
        long w1Id = readJson(w1).get("id").asLong();

        // 幂等：同 requestId 同参数重放首次结果
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", DAY, T_08, T_12, "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(w1Id));
        // 同 requestId 异参数：409
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w1", DAY, T_08, T_12, "G"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 422：与已有时段重叠
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w2", DAY, T_10, T_14, "G"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));
        // 失败不占键：同 requestId 换合法参数成功（端点相接合法）
        MvcResult w2 = postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w2", DAY, T_12, T_18, "G"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value(T_12))
                .andReturn();
        long w2Id = readJson(w2).get("id").asLong();
        // 前端点相接 [06,08) 也合法
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w3", DAY, T_06, T_08, "MATURE"))
                .andExpect(status().isOk());

        // 422：时段跨出运营日
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w4", DAY,
                                "2026-09-22T23:00:00.000+08:00",
                                "2026-09-23T00:30:00.000+08:00", "G"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OUT_OF_DAY"));
        // 400：终点不大于起点
        postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody("req-" + p + "-w5", DAY, T_12, T_08, "G"))
                .andExpect(status().isBadRequest());
        // 400：非法最高分级
        mvc.perform(post("/api/channels/" + channel + "/rating-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(windowBody("req-" + p + "-w6", DAY, T_08, T_10, "ADULT"))))
                .andExpect(status().isBadRequest());

        // 配置查询：按开始时刻升序返回 3 条
        getJson("/api/channels/" + channel + "/rating-windows", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].start").value(T_06))
                .andExpect(jsonPath("$[1].id").value(w1Id))
                .andExpect(jsonPath("$[2].id").value(w2Id));
        // 404：查询不存在频道的配置
        getJson("/api/channels/" + p + "ghost/rating-windows", DAY)
                .andExpect(status().isNotFound());

        // 修改主流程：排除自身的重叠校验，[12,18) 收紧为 [13,18) PG
        putJson("/api/channels/" + channel + "/rating-windows/" + w2Id,
                        updateWindowBody("req-" + p + "-u1", T_13, T_18, "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(w2Id))
                .andExpect(jsonPath("$.start").value(T_13))
                .andExpect(jsonPath("$.maxRating").value("PG"));
        // 修改幂等重放
        putJson("/api/channels/" + channel + "/rating-windows/" + w2Id,
                        updateWindowBody("req-" + p + "-u1", T_13, T_18, "PG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(w2Id));
        // 同 requestId 异参数：409
        putJson("/api/channels/" + channel + "/rating-windows/" + w2Id,
                        updateWindowBody("req-" + p + "-u1", T_13, T_18, "G"))
                .andExpect(status().isConflict());
        // 422：修改后与其他时段重叠（[11,13) 与 [08,12) 重叠）
        putJson("/api/channels/" + channel + "/rating-windows/" + w2Id,
                        updateWindowBody("req-" + p + "-u2", T_11, T_13, "PG"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WINDOW_OVERLAP"));
        // 404：时段不存在 / 不属于该频道
        putJson("/api/channels/" + channel + "/rating-windows/999999",
                        updateWindowBody("req-" + p + "-u3", T_13, T_18, "PG"))
                .andExpect(status().isNotFound());
        putJson("/api/channels/" + p + "ch2/rating-windows/" + w2Id,
                        updateWindowBody("req-" + p + "-u4", T_13, T_18, "PG"))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布分级校验与越权拦截 ----------

    @Test
    void publishRatingValidationBlocksAndRecords() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        long windowId = createWindow(channel, "req-" + p + "-w", DAY, T_08, T_12, "G");

        // 草稿：seg1 PG 越级 G 时段、seg2 MATURE 越级 G 时段、seg3 在时段外不受限
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0, List.of(
                                segment(p + "seg-1", p + "pg", T_10, T_11),
                                segment(p + "seg-2", p + "mature", T_11, T_12),
                                segment(p + "seg-3", p + "pg", T_13, T_14))))
                .andExpect(status().isOk());

        // 422：列出全部越级素材及命中时段，不发布草稿
        MvcResult blocked = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$.violations[0].assetId").value(p + "pg"))
                .andExpect(jsonPath("$.violations[0].rating").value("PG"))
                .andExpect(jsonPath("$.violations[0].windowId").value(windowId))
                .andExpect(jsonPath("$.violations[0].windowMaxRating").value("G"))
                .andExpect(jsonPath("$.violations[1].segmentId").value(p + "seg-2"))
                .andExpect(jsonPath("$.violations[1].rating").value("MATURE"))
                .andReturn();
        assertThat(readJson(blocked).get("violations").get(0).get("windowStart").asText())
                .isEqualTo(T_08);

        // 未发布：播出查询仍为无已发布编排
        getJson("/api/channels/" + channel + "/playout?at=" + T_10)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 拦截记录可追溯：2 条 FAIL，publicationId 为 null
        getJson("/api/channels/" + channel + "/rating-checks", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].verdict").value("FAIL"))
                .andExpect(jsonPath("$[0].publicationId").value(nullValue()))
                .andExpect(jsonPath("$[0].windowId").value(windowId))
                .andExpect(jsonPath("$[0].segmentId").value(p + "seg-1"))
                .andExpect(jsonPath("$[1].verdict").value("FAIL"));

        // 失败不占键：放宽时段分级后同 requestId 同参数重试成功
        putJson("/api/channels/" + channel + "/rating-windows/" + windowId,
                        updateWindowBody("req-" + p + "-relax", T_08, T_12, "MATURE"))
                .andExpect(status().isOk());
        MvcResult published = postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();

        // 成功发布的 PASS 记录随快照写入：时段内 2 条带 windowId，时段外 1 条 windowId 为 null
        getJson("/api/channels/" + channel + "/rating-checks", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[2].verdict").value("PASS"))
                .andExpect(jsonPath("$[2].publicationId").value(publicationId))
                .andExpect(jsonPath("$[2].windowId").value(windowId))
                .andExpect(jsonPath("$[3].verdict").value("PASS"))
                .andExpect(jsonPath("$[4].verdict").value("PASS"))
                .andExpect(jsonPath("$[4].segmentId").value(p + "seg-3"))
                .andExpect(jsonPath("$[4].windowId").value(nullValue()));
    }

    @Test
    void unratedAssetTreatedAsMatureInPublishAndBreakin() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        createWindow(channel, "req-" + p + "-w", DAY, T_08, T_12, "PG");

        // 未声明分级素材按 MATURE 参与发布校验：PG 时段内越级
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "unrated", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations[0].assetId").value(p + "unrated"))
                .andExpect(jsonPath("$.violations[0].rating").value("MATURE"));

        // 未声明分级素材按 MATURE 参与插播校验：PG 时段内越级
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b1", p + "unrated", T_10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations[0].rating").value("MATURE"));
    }

    @Test
    void windowConfigChangeDoesNotRewritePublishedSnapshot() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        // 无管控时段时发布 MATURE 素材成功（无限制）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "mature", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 新增严格时段配置不改写已发布快照：播出查询仍命中节目
        long windowId = createWindow(channel, "req-" + p + "-w", DAY, T_08, T_12, "G");
        getJson("/api/channels/" + channel + "/playout?at=" + "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "mature"))
                .andExpect(jsonPath("$.rating").value("MATURE"))
                .andExpect(jsonPath("$.controlWindow.id").value(windowId))
                .andExpect(jsonPath("$.controlWindow.maxRating").value("G"));

        // 新配置影响后续发布：同一草稿再次发布被拦截
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p2", "draftVersion", 1,
                                "expectedPublishedVersion", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"));
    }

    // ---------- 紧急插播分级门禁 ----------

    @Test
    void breakinRatingGateAndIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        long windowId = createWindow(channel, "req-" + p + "-w", DAY, T_08, T_12, "PG");

        // 422：插播素材分级超过插播时刻时段上限，不创建插播
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b1", p + "mature", T_10))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RATING_EXCEEDED"))
                .andExpect(jsonPath("$.violations[0].assetId").value(p + "mature"))
                .andExpect(jsonPath("$.violations[0].windowId").value(windowId))
                .andExpect(jsonPath("$.violations[0].segmentId").value(nullValue()));
        // 失败不占键：同时刻换合规素材、同 requestId 成功
        MvcResult created = postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b1", p + "pg", T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channel))
                .andExpect(jsonPath("$.assetId").value(p + "pg"))
                .andExpect(jsonPath("$.rating").value("PG"))
                .andExpect(jsonPath("$.controlWindowId").value(windowId))
                .andReturn();
        long breakinId = readJson(created).get("id").asLong();
        // 幂等重放：返回首次结果
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b1", p + "pg", T_10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(breakinId));
        // 同 requestId 异参数：409
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b1", p + "pg", T_11))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 时段外无限制：MATURE 素材 13:00 插播成功，controlWindowId 为 null
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b2", p + "mature", T_13))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("MATURE"))
                .andExpect(jsonPath("$.controlWindowId").value(nullValue()));

        // 404：频道 / 素材不存在
        postJson("/api/channels/" + p + "ghost/breakins",
                        breakinBody("req-" + p + "-b3", p + "pg", T_10))
                .andExpect(status().isNotFound());
        postJson("/api/channels/" + channel + "/breakins",
                        breakinBody("req-" + p + "-b4", p + "ghost", T_10))
                .andExpect(status().isNotFound());
    }

    // ---------- 播出查询返回分级与命中时段 ----------

    @Test
    void playoutDecisionReturnsRatingAndHitWindow() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        long windowId = createWindow(channel, "req-" + p + "-w", DAY, T_08, T_12, "PG");
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "pg", T_10, T_11)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 命中节目：返回素材分级与命中时段
        getJson("/api/channels/" + channel + "/playout?at=" + "2026-09-22T10:30:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.rating").value("PG"))
                .andExpect(jsonPath("$.controlWindow.id").value(windowId))
                .andExpect(jsonPath("$.controlWindow.start").value(T_08))
                .andExpect(jsonPath("$.controlWindow.end").value(T_12))
                .andExpect(jsonPath("$.controlWindow.maxRating").value("PG"));

        // 空档保底：保底素材未声明分级按 MATURE 返回，时段外 controlWindow 为 null
        getJson("/api/channels/" + channel + "/playout?at=" + "2026-09-22T15:00:00.000+08:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.rating").value("MATURE"))
                .andExpect(jsonPath("$.controlWindow").value(nullValue()));
    }

    // ---------- 并发：时段配置与发布按提交顺序一致裁决 ----------

    @Test
    void concurrentWindowCreateAndPublishAreConsistent() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        replaceDraftBody("req-" + p + "-d1", 0,
                                segment(p + "seg-1", p + "mature", T_10, T_11)))
                .andExpect(status().isOk());

        // 并发：创建 G 时段 [08,12) 与发布含 MATURE 片段的草稿
        List<Integer> statuses = runConcurrently(2, i -> {
            if (i == 0) {
                return mvc.perform(post("/api/channels/" + channel + "/rating-windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(windowBody("req-" + p + "-cw", DAY, T_08, T_12, "G"))))
                        .andReturn().getResponse().getStatus();
            }
            return mvc.perform(post("/api/channels/" + channel + "/drafts/" + DAY + "/publish")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("requestId", "req-" + p + "-cp",
                                    "draftVersion", 1, "expectedPublishedVersion", 0))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(statuses.get(0)).isEqualTo(200);
        // 发布结果只能是 200（发布先提交，按无限制配置通过）或 422（时段先提交，按新配置拦截）
        assertThat(statuses.get(1)).isIn(200, 422);

        // 结果与校验记录一致，不出现新旧配置混合判定
        MvcResult checks = getJson("/api/channels/" + channel + "/rating-checks", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();
        JsonNode record = readJson(checks).get(0);
        if (statuses.get(1) == 200) {
            assertThat(record.get("verdict").asText()).isEqualTo("PASS");
            assertThat(record.get("publicationId").isIntegralNumber()).isTrue();
            getJson("/api/channels/" + channel + "/playout?at=" + "2026-09-22T10:30:00.000+08:00")
                    .andExpect(jsonPath("$.source").value("PROGRAM"));
        } else {
            assertThat(record.get("verdict").asText()).isEqualTo("FAIL");
            assertThat(record.get("publicationId").isNull()).isTrue();
            getJson("/api/channels/" + channel + "/playout?at=" + "2026-09-22T10:30:00.000+08:00")
                    .andExpect(jsonPath("$.source").value("FALLBACK"));
        }
    }

    @Test
    void concurrentOverlappingWindowCreatesOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannelWithRatedAssets(p);

        List<Integer> statuses = runConcurrently(2, i ->
                mvc.perform(post("/api/channels/" + channel + "/rating-windows")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(windowBody("req-" + p + "-cw" + i, DAY,
                                        i == 0 ? T_08 : T_10, i == 0 ? T_12 : T_14, "G"))))
                        .andReturn().getResponse().getStatus());
        // 两个重叠时段按频道行锁串行：恰一个成功，一个 422 重叠拒绝
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        getJson("/api/channels/" + channel + "/rating-windows", DAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 测试辅助 ----------

    /** 创建频道与分级素材：fallback（未声明）、pg（PG）、mature（MATURE）、unrated（未声明），并授予全天授权。 */
    private String newChannelWithRatedAssets(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "pg", "durationMs", 3600000, "rating", "PG"))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "mature", "durationMs", 3600000, "rating", "MATURE"))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "unrated", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        for (String asset : List.of("pg", "mature", "unrated")) {
            postJson("/api/grants", Map.of(
                            "channelId", p + "ch", "assetId", p + asset,
                            "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                    .andExpect(status().isOk());
        }
        return p + "ch";
    }

    private long createWindow(String channel, String requestId, String businessDay,
                              String start, String end, String maxRating) throws Exception {
        MvcResult result = postJson("/api/channels/" + channel + "/rating-windows",
                        windowBody(requestId, businessDay, start, end, maxRating))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> windowBody(String requestId, String businessDay,
                                                  String start, String end, String maxRating) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("businessDay", businessDay);
        body.put("start", start);
        body.put("end", end);
        body.put("maxRating", maxRating);
        return body;
    }

    private static Map<String, Object> updateWindowBody(String requestId, String start,
                                                        String end, String maxRating) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("start", start);
        body.put("end", end);
        body.put("maxRating", maxRating);
        return body;
    }

    private static Map<String, Object> breakinBody(String requestId, String assetId, String at) {
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

    private ResultActions getJson(String url, String businessDay) throws Exception {
        return mvc.perform(get(url).param("businessDay", businessDay));
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
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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
