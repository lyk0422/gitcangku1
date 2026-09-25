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
 * 紧急字幕（crawl）端到端测试：区域规范化、整数优先级、时间边界、发布快照固化、
 * 审核与黑屏门禁（422 稳定明细）、撤销/改版/更高优先级不改写快照、播放回执与并发幂等。
 * 运行环境为 H2（MODE=MySQL）内存库，唯一约束、行锁与事务提交顺序均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrawlSubtitleApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_1010 = "2026-09-22T10:10:00.000+08:00";
    private static final String T_1020 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_1025 = "2026-09-22T10:25:00.000+08:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_1032 = "2026-09-22T10:32:00.000+08:00";
    private static final String T_1035 = "2026-09-22T10:35:00.000+08:00";
    private static final String T_1038 = "2026-09-22T10:38:00.000+08:00";
    private static final String T_1040 = "2026-09-22T10:40:00.000+08:00";
    private static final String T_1050 = "2026-09-22T10:50:00.000+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 区域规范化与窗口重叠规则 ----------

    @Test
    void regionsNormalizedAndWindowOverlapRules() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "台风红色预警", true);

        // 区域去空白、去重、字典序排序
        createSubtitle(ctx, "req-norm", "s-norm", 5, "tk", 1, T_1020, T_1040,
                        List.of("  east  ", "east", "north", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0]").value("abc"))
                .andExpect(jsonPath("$.regions[1]").value("east"))
                .andExpect(jsonPath("$.regions[2]").value("north"))
                .andExpect(jsonPath("$.regions.length()").value(3));

        // 同区域同优先级正重叠：409
        createSubtitle(ctx, "req-over", "s-over", 5, "tk", 1, T_1030, T_1050, List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SUBTITLE_WINDOW_CONFLICT"));
        // 端点相接合法（10:40 相接）
        createSubtitle(ctx, "req-adj", "s-adj", 5, "tk", 1, T_1040, T_1050, List.of("east"))
                .andExpect(status().isOk());
        // 不同区域同优先级重叠：合法
        createSubtitle(ctx, "req-west", "s-west", 5, "tk", 1, T_1020, T_1040, List.of("west"))
                .andExpect(status().isOk());
        // 同区域不同优先级重叠：合法
        createSubtitle(ctx, "req-p9", "s-p9", 9, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());

        // 非法窗口 400；区域含逗号 400；空区域 400
        createSubtitle(ctx, "req-bad1", "s-bad1", 5, "tk", 1, T_1040, T_1020, List.of("east"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/emergency-subtitles").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(subtitleBody(
                                "req-bad2", ctx.key("s-bad2"), ctx.channel, 5, "tk", 1,
                                T_1020, T_1040, List.of("ea,st")))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/emergency-subtitles").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(subtitleBody(
                                "req-bad3", ctx.key("s-bad3"), ctx.channel, 5, "tk", 1,
                                T_1020, T_1040, List.of()))))
                .andExpect(status().isBadRequest());

        // 引用不存在的文本版本：404；重复 subtitleKey：409（含已撤销键见后续用例）
        createSubtitle(ctx, "req-ghost", "s-ghost", 5, "tk", 99, T_1020, T_1040, List.of("east"))
                .andExpect(status().isNotFound());
        createSubtitle(ctx, "req-dup", "s-norm", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SUBTITLE_KEY"));
    }

    // ---------- 整数优先级与左闭右开时间边界（实时决策） ----------

    @Test
    void priorityWinsAndHalfOpenBoundary() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "低优先级", true);
        createText(ctx, "tk2", 1, "高优先级", true);
        createSubtitle(ctx, "r-lo", "lo", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        createSubtitle(ctx, "r-hi", "hi", 8, "tk2", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());

        // 数值越大优先级越高
        decision(ctx, "east", T_1030)
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("hi")))
                .andExpect(jsonPath("$.priority").value(8))
                .andExpect(jsonPath("$.textContent").value("高优先级"));
        // 起点恰好命中（左闭）
        decision(ctx, "east", T_1020)
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("hi")));
        // 终点恰好不命中（右开）
        decision(ctx, "east", T_1040)
                .andExpect(jsonPath("$.subtitleKey").isEmpty());
        // 窗口外不命中
        decision(ctx, "east", T_1010)
                .andExpect(jsonPath("$.subtitleKey").isEmpty());
        // 其他区域不命中
        decision(ctx, "west", T_1030)
                .andExpect(jsonPath("$.subtitleKey").isEmpty())
                .andExpect(jsonPath("$.region").value("west"));
        // 区域参数规范化（首尾空白）
        decision(ctx, "  east  ", T_1030)
                .andExpect(jsonPath("$.region").value("east"))
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("hi")));
    }

    // ---------- 发布主流程：固化素材、字幕版本、优先级、解析原因 ----------

    @Test
    void publishFreezesSubtitleSnapshot() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "暴雨橙色预警", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east", "west"))
                .andExpect(status().isOk());
        long publicationId = publishProgram(ctx, "seg-1", "req-pub-1");

        // 快照按区域固化两份子片，含文本版本、内容、优先级与解析原因
        MvcResult snapshot = mvc.perform(get("/api/snapshots/" + publicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.segments[0].assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.subtitles.length()").value(2))
                .andExpect(jsonPath("$.subtitles[0].region").value("east"))
                .andExpect(jsonPath("$.subtitles[0].textVersion").value(1))
                .andExpect(jsonPath("$.subtitles[0].textContent").value("暴雨橙色预警"))
                .andExpect(jsonPath("$.subtitles[0].priority").value(5))
                .andExpect(jsonPath("$.subtitles[0].subtitleKey").value(ctx.key("sub")))
                .andExpect(jsonPath("$.subtitles[0].reason").value(
                        org.hamcrest.Matchers.containsString("selected=" + ctx.key("sub"))))
                .andExpect(jsonPath("$.subtitles[1].region").value("west"))
                .andReturn();
        assertThat(readJson(snapshot).get("subtitles").get(0).get("start").asText())
                .isEqualTo(T_1020);
        assertThat(readJson(snapshot).get("subtitles").get(0).get("end").asText())
                .isEqualTo(T_1040);

        // 业务日最新快照查询一致
        mvc.perform(get("/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId));

        // 时间片切分：字幕窗口内嵌节目片，两端之外不固化
        decision(ctx, "east", T_1010)
                .andExpect(jsonPath("$.subtitleKey").isEmpty());
    }

    // ---------- 发布后撤销字幕、修改文本、新建更高优先级字幕均不改写快照 ----------

    @Test
    void snapshotImmutableAfterRevokeTextChangeAndHigherPriority() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "旧文本", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        long publicationId = publishProgram(ctx, "seg-1", "req-pub-imm");

        // 撤销字幕
        postJson("/api/emergency-subtitles/" + ctx.key("sub") + "/revoke",
                        Map.of("requestId", "req-revoke-sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // “修改文本”：新增 v2（内容不可变，只能新增版本）
        createText(ctx, "tk", 2, "新文本", true);
        // 新建更高优先级字幕引用新文本
        createText(ctx, "tk3", 1, "紧急插播字幕", true);
        createSubtitle(ctx, "r-high", "high", 9, "tk3", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());

        // 实时决策反映新状态：高优先级
        decision(ctx, "east", T_1030)
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("high")))
                .andExpect(jsonPath("$.textContent").value("紧急插播字幕"));

        // 但已发布快照不变：仍旧字幕、旧文本、旧优先级
        mvc.perform(get("/api/snapshots/" + publicationId))
                .andExpect(jsonPath("$.subtitles[0].subtitleKey").value(ctx.key("sub")))
                .andExpect(jsonPath("$.subtitles[0].textVersion").value(1))
                .andExpect(jsonPath("$.subtitles[0].textContent").value("旧文本"))
                .andExpect(jsonPath("$.subtitles[0].priority").value(5));

        // 播放回执按发布快照：仍旧
        PlaybackCheck old = receipt("crawl-imm-1", ctx, "east", T_1030);
        old.actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("sub")))
                .andExpect(jsonPath("$.textVersion").value(1))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.publishedVersion").value(1));
        assertThat(old.json.get("fingerprint").asText()).hasSize(64).matches("[0-9a-f]{64}");

        // 再次发布为版本 2 才固化新字幕
        long secondId = publish(ctx, "req-pub-imm-2", 1, 1);
        mvc.perform(get("/api/snapshots/" + secondId))
                .andExpect(jsonPath("$.subtitles[0].subtitleKey").value(ctx.key("high")))
                .andExpect(jsonPath("$.subtitles[0].priority").value(9))
                .andExpect(jsonPath("$.subtitles[0].textContent").value("紧急插播字幕"));
        // 旧回执重放（同 crawlKey）仍返回版本 1 的旧内容
        receipt("crawl-imm-1", ctx, "east", T_1030).actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("sub")))
                .andExpect(jsonPath("$.publishedVersion").value(1));
        // 新回执按版本 2
        PlaybackCheck receiptV2 = receipt("crawl-imm-2", ctx, "east", T_1030);
        receiptV2.actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("high")))
                .andExpect(jsonPath("$.publishedVersion").value(2));
        // 指纹含节目单版本/窗口/优先级/文本版本：版本 1 与版本 2 指纹必须不同
        String fpV1 = old.json.get("fingerprint").asText();
        String fpV2 = receiptV2.json.get("fingerprint").asText();
        assertThat(fpV1).isNotEqualTo(fpV2);
    }

    // ---------- 审核门禁：未审核 → 整次 422，不发布部分区域，失败不占键 ----------

    @Test
    void publishBlockedWhenTextNotApprovedAndNoPartialRegions() throws Exception {
        Ctx ctx = newContext();
        // east 文本已审核，west 文本未审核
        createText(ctx, "tk-east", 1, "EAST 已审核", true);
        createText(ctx, "tk-west", 1, "WEST 未审核", false);
        createSubtitle(ctx, "r-se", "se", 5, "tk-east", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        createSubtitle(ctx, "r-sw", "sw", 5, "tk-west", 1, T_1020, T_1040, List.of("west"))
                .andExpect(status().isOk());
        putDraft(ctx, "seg-1", "req-draft-1");

        // 整次发布 422，错误码可区分，且响应本身稳定列出区域与窗口
        postPublish(ctx, "req-pub-block", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SUBTITLE_TEXT_NOT_APPROVED"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].region").value("west"))
                .andExpect(jsonPath("$.items[0].start").value(T_1020))
                .andExpect(jsonPath("$.items[0].end").value(T_1040))
                .andExpect(jsonPath("$.items[0].subtitleKey").value(ctx.key("sw")))
                .andExpect(jsonPath("$.items[0].textVersion").value(1));

        // 稳定列出区域与窗口：只有 west 被阻断，east 不被部分发布
        mvc.perform(get("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish-block"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUBTITLE_TEXT_NOT_APPROVED"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].region").value("west"))
                .andExpect(jsonPath("$.items[0].start").value(T_1020))
                .andExpect(jsonPath("$.items[0].end").value(T_1040))
                .andExpect(jsonPath("$.items[0].subtitleKey").value(ctx.key("sw")))
                .andExpect(jsonPath("$.items[0].textVersion").value(1));

        // 无快照产生、发布版本仍为 0（无半成品）
        mvc.perform(get("/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                .andExpect(status().isNotFound());

        // 失败不占 requestId：west 审核通过后用同一 requestId 重放发布，成功且版本为 1
        approveText(ctx, "tk-west", 1, "req-approve-west");
        long publicationId = publish(ctx, "req-pub-block", 1, 0);
        mvc.perform(get("/api/snapshots/" + publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.subtitles.length()").value(2));
    }

    // ---------- 黑屏门禁：正重叠 422，端点相接合法 ----------

    @Test
    void blackoutConflictBlocksPublishButAdjacentIsLegal() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "黑屏期字幕", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        putDraft(ctx, "seg-1", "req-draft-bo");

        // 黑屏 10:30-10:35 east：与字幕正重叠
        postJson("/api/blackouts", blackoutBody("req-bo-1", ctx.channel, T_1030, T_1035,
                        List.of("east")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions[0]").value("east"))
                .andExpect(jsonPath("$.id").isNumber());
        postPublish(ctx, "req-pub-bo", 1, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SUBTITLE_BLACKOUT_CONFLICT"))
                .andExpect(jsonPath("$.items[0].region").value("east"))
                .andExpect(jsonPath("$.items[0].start").value(T_1030))
                .andExpect(jsonPath("$.items[0].end").value(T_1035))
                .andExpect(jsonPath("$.items[0].subtitleKey").value(ctx.key("sub")));
        mvc.perform(get("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish-block"))
                .andExpect(jsonPath("$.code").value("SUBTITLE_BLACKOUT_CONFLICT"))
                .andExpect(jsonPath("$.items[0].region").value("east"))
                .andExpect(jsonPath("$.items[0].start").value(T_1030))
                .andExpect(jsonPath("$.items[0].end").value(T_1035))
                .andExpect(jsonPath("$.items[0].subtitleKey").value(ctx.key("sub")));
        mvc.perform(get("/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                .andExpect(status().isNotFound());

        // 黑屏只影响共同区域：west 区域不受影响（新建 west 字幕仍可随发布成功——
        // 但当前已被 east 阻断，整次拒绝，验证不发布部分区域）
        // 端点相接合法：撤销无法删除黑屏（黑屏不可变），改用相接窗口的新频道验证
        Ctx other = newContext();
        createText(other, "tk", 1, "相接合法", true);
        createSubtitle(other, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        postJson("/api/blackouts", blackoutBody("req-bo-2", other.channel, T_1040, T_1050,
                        List.of("east")))
                .andExpect(status().isOk());
        long publicationId = publishProgram(other, "seg-o", "req-pub-ok");
        mvc.perform(get("/api/snapshots/" + publicationId))
                .andExpect(jsonPath("$.subtitles.length()").value(1))
                .andExpect(jsonPath("$.subtitles[0].region").value("east"));

        // 其他区域黑屏不阻断本区域
        Ctx third = newContext();
        createText(third, "tk", 1, "区域无关", true);
        createSubtitle(third, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        postJson("/api/blackouts", blackoutBody("req-bo-3", third.channel, T_1030, T_1035,
                        List.of("west")))
                .andExpect(status().isOk());
        long thirdId = publishProgram(third, "seg-t", "req-pub-third");
        mvc.perform(get("/api/snapshots/" + thirdId))
                .andExpect(jsonPath("$.subtitles.length()").value(1));

        // 黑屏非法窗口 400；失败不产生效果
        postJson("/api/blackouts", blackoutBody("req-bo-bad", ctx.channel, T_1035, T_1030,
                        List.of("east")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 播放回执：快照确认、端点不覆盖、幂等、失败不占键 ----------

    @Test
    void receiptConfirmsSnapshotWithIdempotencyAndBoundary() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "回执文本", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        long publicationId = publishProgram(ctx, "seg-1", "req-pub-rc");

        // 覆盖中
        receipt("ck-1", ctx, "east", T_1030).actions
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.segmentId").value(ctx.key("seg-1")))
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("sub")))
                .andExpect(jsonPath("$.textVersion").value(1))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.overlayStart").value(T_1020))
                .andExpect(jsonPath("$.overlayEnd").value(T_1040))
                .andExpect(jsonPath("$.region").value("east"));
        // 同 crawlKey 重放：返回首次结果
        JsonNode first = receipt("ck-1", ctx, "east", T_1030).json;
        JsonNode replay = receipt("ck-1", ctx, "east", T_1030).json;
        assertThat(replay).isEqualTo(first);
        // 同 crawlKey 改参（时刻）：409
        receipt("ck-1", ctx, "east", T_1035).actions
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CRAWL_KEY_CONFLICT"));
        // 同 crawlKey 改区域：409
        receipt("ck-1", ctx, "west", T_1030).actions
                .andExpect(status().isConflict());

        // 字幕结束端点恰好：不覆盖
        receipt("ck-2", ctx, "east", T_1040).actions
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitleKey").isEmpty())
                .andExpect(jsonPath("$.overlayStart").isEmpty())
                .andExpect(jsonPath("$.overlayEnd").isEmpty())
                .andExpect(jsonPath("$.priority").isEmpty());
        // 窗口外无覆盖
        receipt("ck-3", ctx, "east", T_1010).actions
                .andExpect(jsonPath("$.subtitleKey").isEmpty());
        // 无覆盖与有覆盖指纹必须不同；无覆盖指纹两次一致
        String fpNone = receipt("ck-2", ctx, "east", T_1040).json.get("fingerprint").asText();
        String fpNone2 = receipt("ck-3", ctx, "east", T_1010).json.get("fingerprint").asText();
        String fpOverlay = receipt("ck-1", ctx, "east", T_1030).json.get("fingerprint").asText();
        assertThat(fpNone).isNotEqualTo(fpOverlay);
        // 不同时刻窗口均无覆盖，指纹只含版本/区域/空窗口段 → 相同
        assertThat(fpNone).isEqualTo(fpNone2);

        // 无发布节目单：回执 422，失败不占 crawlKey
        Ctx bare = newContext();
        receipt("ck-bare", bare, "east", T_1030).actions
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_PUBLISHED_SCHEDULE"));
        publishProgram(bare, "seg-b", "req-pub-bare");
        // 同一 crawlKey 随后成功
        receipt("ck-bare", bare, "east", T_1030).actions
                .andExpect(status().isOk());

        // 频道不存在 404
        mvc.perform(post("/api/playback-receipts").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(receiptBody(
                                "ck-ghost", ctx.prefix + "ghost", "east", T_1030))))
                .andExpect(status().isNotFound());
    }

    // ---------- 文本与审核、撤销幂等 ----------

    @Test
    void textApprovalAndSubtitleRevokeIdempotency() throws Exception {
        Ctx ctx = newContext();
        // 创建文本版本
        createText(ctx, "tk", 1, "A", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvedAt").isEmpty());
        // 重复创建同版本（新 requestId）：409
        postJson("/api/subtitle-texts", Map.of(
                        "requestId", "req-text-dup-" + ctx.prefix,
                        "textKey", ctx.key("tk"), "version", 1, "content", "A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_TEXT_VERSION"));
        // 审核主流程
        approveText(ctx, "tk", 1, "req-ap-1")
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        // 同 requestId 重放：返回首次结果
        approveText(ctx, "tk", 1, "req-ap-1")
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // 新 requestId 重复审核：409
        approveText(ctx, "tk", 1, "req-ap-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SUBTITLE_TEXT_ALREADY_APPROVED"));
        // 不存在的文本版本 404
        approveText(ctx, "tk", 99, "req-ap-3")
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/subtitle-texts/" + ctx.key("tk") + "/versions/1"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 字幕撤销：主流程 + 幂等 + 重复撤销 409 + 同键不复活
        createSubtitle(ctx, "r-s1", "s1", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        postJson("/api/emergency-subtitles/" + ctx.key("s1") + "/revoke",
                        Map.of("requestId", "rv-" + ctx.prefix + "1"))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeRequestId").value("rv-" + ctx.prefix + "1"));
        postJson("/api/emergency-subtitles/" + ctx.key("s1") + "/revoke",
                        Map.of("requestId", "rv-" + ctx.prefix + "1"))
                .andExpect(jsonPath("$.status").value("REVOKED"));
        postJson("/api/emergency-subtitles/" + ctx.key("s1") + "/revoke",
                        Map.of("requestId", "rv-" + ctx.prefix + "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SUBTITLE_NOT_ACTIVE"));
        // 新 requestId 用同键再创建：409，重放不能复活
        createSubtitle(ctx, "r-s2", "s1", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_SUBTITLE_KEY"));
        mvc.perform(get("/api/emergency-subtitles/" + ctx.key("s1")))
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 撤销不存在字幕 404
        postJson("/api/emergency-subtitles/" + ctx.prefix + "ghost/revoke",
                        Map.of("requestId", "rv-" + ctx.prefix + "3"))
                .andExpect(status().isNotFound());
        // 撤销提交后释放同区域同优先级窗口
        createSubtitle(ctx, "r-s3", "s3", 5, "tk", 1, T_1030, T_1035, List.of("east"))
                .andExpect(status().isOk());
    }

    // ---------- 并发：同区域重叠只赢一条；同 crawlKey 并发回执只落一条 ----------

    @Test
    void concurrentSubtitleOverlapAndSameCrawlKey() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "并发", true);

        List<Integer> statuses = runConcurrently(2, i -> mvc
                .perform(post("/api/emergency-subtitles").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(subtitleBody(
                                "cc-req-" + ctx.prefix + i, ctx.key("cc" + i), ctx.channel,
                                5, ctx.key("tk"), 1, T_1020, T_1040, List.of("east")))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 相邻窗口并发：均成功
        List<Integer> adjacent = runConcurrently(2, i -> {
            String start = i == 0 ? T_1020 : T_1030;
            String end = i == 0 ? T_1030 : T_1040;
            return mvc.perform(post("/api/emergency-subtitles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(subtitleBody(
                                    "ad-req-" + ctx.prefix + i, ctx.key("ad" + i), ctx.channel,
                                    3, ctx.key("tk"), 1, start, end, List.of("west")))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(adjacent).containsExactly(200, 200);

        // 同 crawlKey 并发回执：均成功、内容一致，库内只落一条（唯一键 + 重放）
        publishProgram(ctx, "seg-c", "req-pub-cc");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult result = mvc.perform(post("/api/playback-receipts")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(receiptBody(
                                            "same-crawl-key", ctx.channel, "east", T_1030))))
                            .andExpect(status().isOk())
                            .andReturn();
                    return readJson(result);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            JsonNode a = futures.get(0).get(30, TimeUnit.SECONDS);
            JsonNode b = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(a.get("crawlKey").asText()).isEqualTo("same-crawl-key");
            assertThat(b).isEqualTo(a);
        } finally {
            pool.shutdownNow();
        }

        Integer receiptCount = jdbcCount("SELECT COUNT(*) FROM playout_receipt WHERE crawl_key = 'same-crawl-key'");
        assertThat(receiptCount).isEqualTo(1);
        // 同区域同优先级 ACTIVE 窗口（cc0/cc1 只有一条成功）
        Integer activeCount = jdbcCount("SELECT COUNT(*) FROM playout_emergency_subtitle"
                + " WHERE channel_id = '" + ctx.channel + "' AND priority = 5 AND status = 'ACTIVE'");
        assertThat(activeCount).isEqualTo(1);
    }

    // ---------- 多候选优先级切片：低优窗口内嵌高优窗口，发布按片选优 ----------

    @Test
    void publishSlicesByPriorityWithinSegment() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "lo", 1, "低", true);
        createText(ctx, "hi", 1, "高", true);
        // 低优先级覆盖 10:20-10:40，高优先级仅覆盖 10:30-10:35，同区域
        createSubtitle(ctx, "r-lo", "lo", 2, "lo", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        createSubtitle(ctx, "r-hi", "hi", 7, "hi", 1, T_1030, T_1035, List.of("east"))
                .andExpect(status().isOk());
        long publicationId = publishProgram(ctx, "seg-1", "req-pub-slice");

        mvc.perform(get("/api/snapshots/" + publicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitles.length()").value(3))
                .andExpect(jsonPath("$.subtitles[0].subtitleKey").value(ctx.key("lo")))
                .andExpect(jsonPath("$.subtitles[0].start").value(T_1020))
                .andExpect(jsonPath("$.subtitles[0].end").value(T_1030))
                .andExpect(jsonPath("$.subtitles[1].subtitleKey").value(ctx.key("hi")))
                .andExpect(jsonPath("$.subtitles[1].start").value(T_1030))
                .andExpect(jsonPath("$.subtitles[1].end").value(T_1035))
                .andExpect(jsonPath("$.subtitles[2].subtitleKey").value(ctx.key("lo")))
                .andExpect(jsonPath("$.subtitles[2].start").value(T_1035))
                .andExpect(jsonPath("$.subtitles[2].end").value(T_1040));

        // 回执分别确认三段
        receipt("ck-lo-1", ctx, "east", T_1025).actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("lo")))
                .andExpect(jsonPath("$.overlayStart").value(T_1020))
                .andExpect(jsonPath("$.overlayEnd").value(T_1030));
        receipt("ck-hi", ctx, "east", T_1032).actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("hi")))
                .andExpect(jsonPath("$.priority").value(7))
                .andExpect(jsonPath("$.overlayStart").value(T_1030))
                .andExpect(jsonPath("$.overlayEnd").value(T_1035));
        receipt("ck-lo-2", ctx, "east", T_1038).actions
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("lo")));
    }

    // ---------- 回执落在节目空档：422 且失败不占 crawlKey ----------

    @Test
    void receiptOutsideProgramGapFailsAndDoesNotOccupyKey() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "空档文本", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        publishProgram(ctx, "seg-1", "req-pub-gap");

        // 11:30 不在任何节目片（10:00-11:00）内：422，原因可区分
        postJson("/api/playback-receipts", receiptBody("ck-gap", ctx.channel, "east",
                        "2026-09-22T11:30:00.000+08:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("RECEIPT_PLAYOUT_GAP"));
        // 失败不占 crawlKey：同键改到片内时刻成功
        receipt("ck-gap", ctx, "east", T_1030).actions
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitleKey").value(ctx.key("sub")));
    }

    // ---------- 黑屏创建幂等 ----------

    @Test
    void blackoutCreationIsIdempotent() throws Exception {
        Ctx ctx = newContext();
        String body = objectMapper.writeValueAsString(blackoutBody("req-bo-idem", ctx.channel,
                T_1030, T_1035, List.of("east", "west")));
        MvcResult first = mvc.perform(post("/api/blackouts").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk()).andReturn();
        long firstId = readJson(first).get("id").asLong();
        // 同 requestId 同参重放：返回首次结果（同一自增 ID，不重复建窗）
        mvc.perform(post("/api/blackouts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));
        // 同 requestId 改参（频道）：409
        mvc.perform(post("/api/blackouts").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blackoutBody("req-bo-idem",
                                ctx.prefix + "ghost", T_1030, T_1035, List.of("east")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 并发：审核与发布按提交顺序裁决 ----------

    @Test
    void concurrentApproveAndPublishFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "待审核", false);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        putDraft(ctx, "seg-1", "req-draft-race");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publishFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postPublish(ctx, "req-pub-race", 1, 0)
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> approveFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return approveText(ctx, "tk", 1, "req-approve-race")
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
            int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);

            // 审核最终必然成功；发布取决于提交顺序：先发布则 422，先审核则 200
            assertThat(approveStatus).isEqualTo(200);
            assertThat(publishStatus).isIn(200, 422);
            if (publishStatus == 422) {
                // 发布先提交：无快照，阻断原因留痕；审核后重新发布成功
                mvc.perform(get("/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                        .andExpect(status().isNotFound());
                long publicationId = publish(ctx, "req-pub-race-retry", 1, 0);
                mvc.perform(get("/api/snapshots/" + publicationId))
                        .andExpect(jsonPath("$.subtitles[0].textContent").value("待审核"));
            } else {
                // 审核先提交：发布版本 1 已固化字幕
                mvc.perform(get("/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                        .andExpect(jsonPath("$.publishedVersion").value(1))
                        .andExpect(jsonPath("$.subtitles[0].subtitleKey").value(ctx.key("sub")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 并发：撤销字幕与发布按提交顺序裁决 ----------

    @Test
    void concurrentRevokeAndPublishFollowsCommitOrder() throws Exception {
        Ctx ctx = newContext();
        createText(ctx, "tk", 1, "撤销赛跑", true);
        createSubtitle(ctx, "r-sub", "sub", 5, "tk", 1, T_1020, T_1040, List.of("east"))
                .andExpect(status().isOk());
        putDraft(ctx, "seg-1", "req-draft-rv");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publishFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postPublish(ctx, "req-pub-rv", 1, 0)
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postJson("/api/emergency-subtitles/" + ctx.key("sub") + "/revoke",
                                Map.of("requestId", "req-revoke-race"))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            assertThat(revokeStatus).isEqualTo(200);
            // 撤销不是发布阻断条件：发布必然成功；提交顺序只决定快照是否固化该字幕
            assertThat(publishStatus).isEqualTo(200);
            // 撤销最终生效，实时决策不再命中该字幕
            decision(ctx, "east", T_1030)
                    .andExpect(jsonPath("$.subtitleKey").isEmpty());
            MvcResult latest = mvc.perform(get(
                            "/api/channels/" + ctx.channel + "/snapshots/" + DAY + "/latest"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode snapshot = readJson(latest);
            long frozen = snapshot.get("subtitles").size();
            // 发布先提交：字幕被固化（撤销不改写快照）；撤销先提交：快照无字幕
            assertThat(frozen).isBetween(0L, 1L);
            if (frozen == 1) {
                assertThat(snapshot.get("subtitles").get(0).get("subtitleKey").asText())
                        .isEqualTo(ctx.key("sub"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 测试辅助 ----------

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Integer jdbcCount(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private final class Ctx {
        private final String prefix = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private long movieGrant;

        private String key(String shortKey) {
            return prefix + shortKey;
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.fallbackAsset = ctx.prefix + "fallback";
        ctx.movieAsset = ctx.prefix + "movie";
        ctx.channel = ctx.prefix + "ch";
        postJson("/api/assets", Map.of("id", ctx.fallbackAsset, "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", ctx.movieAsset, "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", ctx.channel, "fallbackAssetId", ctx.fallbackAsset))
                .andExpect(status().isOk());
        MvcResult grant = postJson("/api/grants", Map.of(
                        "channelId", ctx.channel, "assetId", ctx.movieAsset,
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk()).andReturn();
        ctx.movieGrant = readJson(grant).get("id").asLong();
        return ctx;
    }

    private ResultActions createText(Ctx ctx, String shortKey, int version, String content,
                                     boolean approve) throws Exception {
        ResultActions actions = postJson("/api/subtitle-texts", Map.of(
                "requestId", "req-text-" + ctx.prefix + shortKey + "-" + version,
                "textKey", ctx.key(shortKey), "version", version, "content", content));
        if (approve) {
            actions.andExpect(status().isOk());
            approveText(ctx, shortKey, version, "req-approve-" + ctx.prefix + shortKey + "-" + version);
        }
        return actions;
    }

    private ResultActions approveText(Ctx ctx, String shortKey, int version, String requestId)
            throws Exception {
        return postJson("/api/subtitle-texts/" + ctx.key(shortKey)
                        + "/versions/" + version + "/approve", Map.of("requestId", requestId));
    }

    private ResultActions createSubtitle(Ctx ctx, String requestId, String shortKey, int priority,
                                         String textShortKey, int textVersion,
                                         String start, String end, List<String> regions)
            throws Exception {
        return postJson("/api/emergency-subtitles", subtitleBody("cs-" + ctx.prefix + requestId,
                ctx.key(shortKey), ctx.channel, priority, ctx.key(textShortKey), textVersion,
                start, end, regions));
    }

    private static Map<String, Object> subtitleBody(String requestId, String subtitleKey,
                                                    String channelId, int priority, String textKey,
                                                    int textVersion, String start, String end,
                                                    List<String> regions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("subtitleKey", subtitleKey);
        body.put("channelId", channelId);
        body.put("priority", priority);
        body.put("textKey", textKey);
        body.put("textVersion", textVersion);
        body.put("start", start);
        body.put("end", end);
        body.put("regions", regions);
        return body;
    }

    private static Map<String, Object> blackoutBody(String requestId, String channelId,
                                                    String start, String end, List<String> regions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("channelId", channelId);
        body.put("start", start);
        body.put("end", end);
        body.put("regions", regions);
        return body;
    }

    private void putDraft(Ctx ctx, String segmentShortId, String requestId) throws Exception {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.key(segmentShortId));
        segment.put("assetId", ctx.movieAsset);
        segment.put("start", T_10);
        segment.put("end", T_11);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", requestId);
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", List.of(segment));
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
    }

    private ResultActions postPublish(Ctx ctx, String requestId, long draftVersion,
                                      long expectedPublishedVersion) throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/publish", Map.of(
                "requestId", requestId, "draftVersion", draftVersion,
                "expectedPublishedVersion", expectedPublishedVersion));
    }

    private long publish(Ctx ctx, String requestId, long draftVersion,
                         long expectedPublishedVersion) throws Exception {
        MvcResult result = postPublish(ctx, requestId, draftVersion, expectedPublishedVersion)
                .andExpect(status().isOk()).andReturn();
        return readJson(result).get("publicationId").asLong();
    }

    private long publishProgram(Ctx ctx, String segmentShortId, String publishRequestId)
            throws Exception {
        putDraft(ctx, segmentShortId, "req-draft-" + ctx.prefix + segmentShortId);
        return publish(ctx, publishRequestId, 1, 0);
    }

    private ResultActions decision(Ctx ctx, String region, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/subtitle-decision")
                .param("region", region).param("at", at));
    }

    private record PlaybackCheck(ResultActions actions, JsonNode json) {
    }

    private PlaybackCheck receipt(String crawlKey, Ctx ctx, String region, String at)
            throws Exception {
        MvcResult result = postJson("/api/playback-receipts",
                receiptBody(crawlKey, ctx.channel, region, at)).andReturn();
        JsonNode json = result.getResponse().getStatus() == 200
                ? readJson(result) : objectMapper.createObjectNode();
        ResultActions actions = mvc.perform(post("/api/playback-receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        receiptBody(crawlKey, ctx.channel, region, at))));
        // 同键重放必须返回与首次一致的结果；对失败场景（如改参 409）两次行为同样一致。
        return new PlaybackCheck(actions, json);
    }

    private static Map<String, Object> receiptBody(String crawlKey, String channelId,
                                                   String region, String at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("crawlKey", crawlKey);
        body.put("channelId", channelId);
        body.put("region", region);
        body.put("at", at);
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
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
