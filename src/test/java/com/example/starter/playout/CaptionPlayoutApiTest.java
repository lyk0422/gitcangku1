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
 * 紧急字幕与节目回执 API 端到端测试：区域解析、整数优先级、UTC 时间边界、发布快照固化、
 * 审核门禁、黑屏阻断与 crawlKey 并发幂等。全部经真实 H2（MODE=MySQL）数据库验证
 * 唯一约束、行锁、事务回滚与提交顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaptionPlayoutApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    // 节目：10:00-11:00 (+08:00) == 02:00-03:00 (UTC)
    private static final String SEG_START = "2026-09-22T10:00:00.000+08:00";
    private static final String SEG_END = "2026-09-22T11:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 文本版本：创建、审核、重复审核 ----------

    @Test
    void captionTextCreateReviewAndErrorBranches() throws Exception {
        Ctx ctx = newContext();

        // 创建即 PENDING
        postJson("/api/caption-texts", Map.of(
                        "crawlKey", ctx.ck("txt-1"),
                        "versionId", ctx.tv("v1"),
                        "content", "紧急通知 v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(ctx.tv("v1")))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reviewedAt").isEmpty());

        // 同 crawlKey 重放：返回首次结果
        postJson("/api/caption-texts", Map.of(
                        "crawlKey", ctx.ck("txt-1"),
                        "versionId", ctx.tv("v1"),
                        "content", "紧急通知 v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"));
        // 同 crawlKey 改内容：409
        postJson("/api/caption-texts", Map.of(
                        "crawlKey", ctx.ck("txt-1"),
                        "versionId", ctx.tv("v1"),
                        "content", "被篡改的内容"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CRAWL_KEY_CONFLICT"));

        // 409：版本 ID 重复
        postJson("/api/caption-texts", Map.of(
                        "crawlKey", ctx.ck("txt-1-dup"),
                        "versionId", ctx.tv("v1"),
                        "content", "另一个内容"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_TEXT_VERSION"));

        // 审核通过
        postJson("/api/caption-texts/" + ctx.tv("v1") + "/review", Map.of(
                        "crawlKey", ctx.ck("rev-1"), "decision", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedAt").isNotEmpty());
        // 同 crawlKey 重放审核：返回首次 APPROVED
        postJson("/api/caption-texts/" + ctx.tv("v1") + "/review", Map.of(
                        "crawlKey", ctx.ck("rev-1"), "decision", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"));
        // 新 crawlKey 重复审核：409
        postJson("/api/caption-texts/" + ctx.tv("v1") + "/review", Map.of(
                        "crawlKey", ctx.ck("rev-1-again"), "decision", "APPROVED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TEXT_ALREADY_REVIEWED"));
        // 查询
        getJson("/api/caption-texts/" + ctx.tv("v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.content").value("紧急通知 v1"));
        // 404
        getJson("/api/caption-texts/" + ctx.prefix + "ghost").andExpect(status().isNotFound());

        // 驳回路径
        postJson("/api/caption-texts", Map.of(
                        "crawlKey", ctx.ck("txt-2"),
                        "versionId", ctx.tv("v2"),
                        "content", "待定稿"))
                .andExpect(status().isOk());
        postJson("/api/caption-texts/" + ctx.tv("v2") + "/review", Map.of(
                        "crawlKey", ctx.ck("rev-2"), "decision", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));
    }

    // ---------- 字幕创建：规范化、同级重叠、端点相接、整数优先级 ----------

    @Test
    void captionCreateNormalizationOverlapAndAdjacency() throws Exception {
        Ctx ctx = newContext();
        String tv = ctx.approveText("cap", "台风红色预警");

        // 区域集合排序去重规范化：["south","east","east"] -> ["east","south"]
        MvcResult created = createCaption(ctx, "c1", "cap-1", 5, tv,
                "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z",
                List.of("south", "east", "east"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.regions[0]").value("east"))
                .andExpect(jsonPath("$.regions[1]").value("south"))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andReturn();
        long captionId = readJson(created).get("id").asLong();
        assertThat(captionId).isPositive();

        // 409：east 区域同优先级窗口部分重叠
        createCaption(ctx, "c2", "cap-2", 5, tv,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:50:00Z", List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CAPTION_INTERVAL_CONFLICT"));
        // 409：south 区域同样冲突
        createCaption(ctx, "c3", "cap-3", 5, tv,
                        "2026-09-22T02:35:00Z", "2026-09-22T02:45:00Z", List.of("south"))
                .andExpect(status().isConflict());
        // 不同区域同优先级重叠：合法
        createCaption(ctx, "c4", "cap-4", 5, tv,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:50:00Z", List.of("north"))
                .andExpect(status().isOk());
        // 同区域不同优先级重叠：合法（整数优先级，9 高于 5）
        createCaption(ctx, "c5", "cap-5", 9, tv,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:50:00Z", List.of("east"))
                .andExpect(status().isOk());
        // 端点相接合法：east 同优先级新窗口 02:40 起
        createCaption(ctx, "c6", "cap-6", 5, tv,
                        "2026-09-22T02:40:00Z", "2026-09-22T02:50:00Z", List.of("east"))
                .andExpect(status().isOk());
        // 400：终点不大于起点
        createCaption(ctx, "c7", "cap-7", 5, tv,
                        "2026-09-22T02:50:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isBadRequest());
        // 400：空区域集合
        mvc.perform(post("/api/emergency-captions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "crawlKey", ctx.ck("c8"), "captionKey", ctx.cap("cap-8"),
                                "channelId", ctx.channel, "priority", 5, "textVersionId", tv,
                                "start", "2026-09-22T02:00:00Z", "end", "2026-09-22T02:10:00Z",
                                "regions", List.of()))))
                .andExpect(status().isBadRequest());
        // 404：频道不存在 / 文本版本不存在
        createCaptionRaw(ctx.ck("c9"), ctx.cap("cap-9"), ctx.prefix + "ghost", 1, tv,
                        "2026-09-22T02:00:00Z", "2026-09-22T02:10:00Z", List.of("east"))
                .andExpect(status().isNotFound());
        createCaptionRaw(ctx.ck("c10"), ctx.cap("cap-10"), ctx.channel, 1, ctx.prefix + "ghost-tv",
                        "2026-09-22T02:00:00Z", "2026-09-22T02:10:00Z", List.of("east"))
                .andExpect(status().isNotFound());

        // 409：重复 captionKey；失败回滚不产生第二条
        createCaption(ctx, "c11", "cap-1", 5, tv,
                        "2026-09-22T05:00:00Z", "2026-09-22T05:10:00Z", List.of("north"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_CAPTION_KEY"));
    }

    @Test
    void revokeIsTerminalReleasesWindowAndDoesNotResurrect() throws Exception {
        Ctx ctx = newContext();
        String tv = ctx.approveText("rv", "预警");
        createCaption(ctx, "rc1", "rcap-1", 5, tv,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isOk());

        // 撤销
        postJson("/api/emergency-captions/" + ctx.cap("rcap-1") + "/revoke",
                        Map.of("crawlKey", ctx.ck("rrv1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeCrawlKey").value(ctx.ck("rrv1")))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());
        // 同 crawlKey 重放：仍为首次撤销结果
        postJson("/api/emergency-captions/" + ctx.cap("rcap-1") + "/revoke",
                        Map.of("crawlKey", ctx.ck("rrv1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 新 crawlKey 重复撤销：409
        postJson("/api/emergency-captions/" + ctx.cap("rcap-1") + "/revoke",
                        Map.of("crawlKey", ctx.ck("rrv2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CAPTION_NOT_ACTIVE"));
        // 同键重建不能复活
        createCaption(ctx, "rc2", "rcap-1", 5, tv,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_CAPTION_KEY"));
        // 撤销提交后释放同区域同优先级窗口
        createCaption(ctx, "rc3", "rcap-3", 5, tv,
                        "2026-09-22T02:25:00Z", "2026-09-22T02:35:00Z", List.of("east"))
                .andExpect(status().isOk());
        // 404 撤销
        postJson("/api/emergency-captions/" + ctx.prefix + "ghost/revoke",
                        Map.of("crawlKey", ctx.ck("rrv404")))
                .andExpect(status().isNotFound());
    }

    // ---------- 区域实时决策：优先级、端点、审核 ----------

    @Test
    void captionDecisionPicksHighestPriorityAndHonorsBoundary() throws Exception {
        Ctx ctx = newContext();
        String approved = ctx.approveText("d1", "高优先级文本");
        String pending = ctx.newText("d2", "待审核文本");
        createCaption(ctx, "dc1", "dcap-lo", 3, approved,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isOk());
        createCaption(ctx, "dc2", "dcap-hi", 8, pending,
                        "2026-09-22T02:25:00Z", "2026-09-22T02:35:00Z", List.of("east"))
                .andExpect(status().isOk());

        // 02:30 命中最高优先级（未审核也实时返回，原因标注未审核）
        decision(ctx, "east", "2026-09-22T02:30:00Z")
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("dcap-hi")))
                .andExpect(jsonPath("$.priority").value(8))
                .andExpect(jsonPath("$.text").value("待审核文本"))
                .andExpect(jsonPath("$.reason").value("TEXT_NOT_APPROVED"));
        // 02:22 只有低优先级已审核字幕
        decision(ctx, "east", "2026-09-22T02:22:00Z")
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("dcap-lo")))
                .andExpect(jsonPath("$.priority").value(3))
                .andExpect(jsonPath("$.reason").value("CAPTION_WIN"));
        // 窗口起点命中（左闭）
        decision(ctx, "east", "2026-09-22T02:20:00Z")
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("dcap-lo")));
        // 结束端点恰好不再覆盖
        decision(ctx, "east", "2026-09-22T02:40:00Z")
                .andExpect(jsonPath("$.captionKey").isEmpty())
                .andExpect(jsonPath("$.reason").value("NO_CAPTION"));
        // 无该区域字幕
        decision(ctx, "west", "2026-09-22T02:30:00Z")
                .andExpect(jsonPath("$.reason").value("NO_CAPTION"));
        // 404
        mvc.perform(get("/api/channels/" + ctx.prefix + "ghost/caption-decision")
                        .param("region", "east").param("at", "2026-09-22T02:30:00Z"))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布主流程：时间片、优先级、快照固化与回执 ----------

    @Test
    void publishFreezesCaptionDecisionsAndReceiptConfirmsSnapshot() throws Exception {
        Ctx ctx = newContext();
        prepareProgram(ctx);
        String tv5 = ctx.approveText("p5", "常规滚动字幕");
        String tv9 = ctx.approveText("p9", "最高优先级插播字幕");

        // east：p5 覆盖 02:20-02:40；p9 覆盖 02:25-02:30
        createCaption(ctx, "pc1", "pcap-5", 5, tv5,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east", "south"))
                .andExpect(status().isOk());
        createCaption(ctx, "pc2", "pcap-9", 9, tv9,
                        "2026-09-22T02:25:00Z", "2026-09-22T02:30:00Z", List.of("east"))
                .andExpect(status().isOk());

        MvcResult pub = captionPublish(ctx, "pp1", 1, 0, List.of("south", "east"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.draftVersion").value(1))
                .andReturn();
        JsonNode pubJson = readJson(pub);
        long publicationId = pubJson.get("publicationId").asLong();

        // 响应中的固化决策按区域、起点稳定排序：east 三片 + south 一片
        List<JsonNode> captions = collect(pubJson.get("captions"));
        assertThat(captions).hasSize(4);
        assertThat(captions.get(0).get("region").asText()).isEqualTo("east");
        assertThat(captions.get(0).get("captionKey").asText()).isEqualTo(ctx.cap("pcap-5"));
        assertThat(captions.get(0).get("start").asText()).isEqualTo("2026-09-22T10:20:00.000+08:00");
        assertThat(captions.get(1).get("captionKey").asText()).isEqualTo(ctx.cap("pcap-9"));
        assertThat(captions.get(1).get("priority").asInt()).isEqualTo(9);
        assertThat(captions.get(1).get("textVersionId").asText()).isEqualTo(tv9);
        assertThat(captions.get(1).get("text").asText()).isEqualTo("最高优先级插播字幕");
        assertThat(captions.get(2).get("captionKey").asText()).isEqualTo(ctx.cap("pcap-5"));
        assertThat(captions.get(3).get("region").asText()).isEqualTo("south");
        // 端点切片均标注 CAPTION_BOUNDARY
        for (JsonNode c : captions) {
            assertThat(c.get("reason").asText()).isEqualTo("CAPTION_BOUNDARY");
        }

        // 发布后撤销字幕、改挂新文本版本并新建更高优先级字幕：不得改写快照
        postJson("/api/emergency-captions/" + ctx.cap("pcap-9") + "/revoke",
                Map.of("crawlKey", ctx.ck("prv9"))).andExpect(status().isOk());
        String tv99 = ctx.approveText("p99", "事后才出现的字幕");
        createCaption(ctx, "pc3", "pcap-99", 99, tv99,
                        "2026-09-22T02:00:00Z", "2026-09-22T03:00:00Z", List.of("east"))
                .andExpect(status().isOk());

        // 快照查询保持发布时内容
        getJson("/api/publications/" + publicationId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captions.length()").value(4))
                .andExpect(jsonPath("$.captions[1].captionKey").value(ctx.cap("pcap-9")))
                .andExpect(jsonPath("$.captions[1].text").value("最高优先级插播字幕"));
        getJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/latest-publication")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId));

        // 回执按快照确认：02:27 命中固化的 p9（即使它已撤销、即使已有 p99）
        confirm(ctx, "rcpt-1", "east", "2026-09-22T02:27:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId))
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.assetId").value(ctx.movieAsset))
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("pcap-9")))
                .andExpect(jsonPath("$.priority").value(9))
                .andExpect(jsonPath("$.textVersionId").value(tv9))
                .andExpect(jsonPath("$.captionText").value("最高优先级插播字幕"))
                .andExpect(jsonPath("$.reason").value("CAPTION_CONFIRMED"));
        // 02:22 命中 p5
        confirm(ctx, "rcpt-2", "east", "2026-09-22T02:22:00Z")
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("pcap-5")))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.reason").value("CAPTION_CONFIRMED"));
        // 02:30 恰为 p9 结束端点，但 p5 的固化时间片自 02:30 起继续覆盖：确认到 p5
        confirm(ctx, "rcpt-3", "east", "2026-09-22T02:30:00Z")
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("pcap-5")))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.reason").value("CAPTION_CONFIRMED"));
        // 02:40 为最后一条字幕结束端点且之后无覆盖：不再覆盖，原因 CAPTION_END_EXACT，字幕字段为空
        confirm(ctx, "rcpt-4", "east", "2026-09-22T02:40:00Z")
                .andExpect(jsonPath("$.captionKey").isEmpty())
                .andExpect(jsonPath("$.captionText").isEmpty())
                .andExpect(jsonPath("$.reason").value("CAPTION_END_EXACT"));
        // 区间外无字幕
        confirm(ctx, "rcpt-5", "east", "2026-09-22T02:10:00Z")
                .andExpect(jsonPath("$.captionKey").isEmpty())
                .andExpect(jsonPath("$.reason").value("NO_CAPTION"));
        // 无节目时间：422
        confirm(ctx, "rcpt-6", "east", "2026-09-22T11:30:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYOUT_GAP"));

        // 回执 crawlKey 重放：返回首次回执
        confirm(ctx, "rcpt-1", "east", "2026-09-22T02:27:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("pcap-9")));
        // 同 crawlKey 改时刻：409
        confirm(ctx, "rcpt-1", "east", "2026-09-22T02:28:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CRAWL_KEY_CONFLICT"));
    }

    // ---------- 发布 422：审核门禁 + 黑屏冲突，稳定列出，不留半成品 ----------

    @Test
    void publishBlockedByUnapprovedTextAndBlackoutListsWindowsAndNoPartialState() throws Exception {
        Ctx ctx = newContext();
        prepareProgram(ctx);
        String good = ctx.approveText("b-good", "已审核");
        String pending = ctx.newText("b-bad", "未审核");

        createCaption(ctx, "bc1", "bcap-good", 5, good,
                        "2026-09-22T02:10:00Z", "2026-09-22T02:20:00Z", List.of("east"))
                .andExpect(status().isOk());
        createCaption(ctx, "bc2", "bcap-bad", 5, pending,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:40:00Z", List.of("south"))
                .andExpect(status().isOk());

        // 整次发布 422：south 未审核；east 不受影响也不发布
        MvcResult blocked = captionPublish(ctx, "bp1", 1, 0, List.of("east", "south"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PUBLISH_BLOCKED"))
                .andExpect(jsonPath("$.blocking.length()").value(1))
                .andExpect(jsonPath("$.blocking[0].region").value("south"))
                .andExpect(jsonPath("$.blocking[0].reason").value("TEXT_NOT_APPROVED"))
                .andExpect(jsonPath("$.blocking[0].windowStart").value("2026-09-22T10:30:00.000+08:00"))
                .andExpect(jsonPath("$.blocking[0].windowEnd").value("2026-09-22T10:40:00.000+08:00"))
                .andReturn();
        assertThat(readJson(blocked).get("message").asText()).contains("阻断");
        // 无任何发布残留：最新发布 404
        getJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/latest-publication")
                .andExpect(status().isNotFound());

        // 失败不占 crawlKey：审核通过后同 crawlKey 同参数重试成功
        postJson("/api/caption-texts/" + pending + "/review",
                Map.of("crawlKey", ctx.ck("brev"), "decision", "APPROVED"))
                .andExpect(status().isOk());
        captionPublish(ctx, "bp1", 1, 0, List.of("east", "south"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 第二版发布：黑屏与字幕窗口冲突 → 422，阻断窗口为黑屏窗口
        String tv2 = ctx.approveText("b2", "第二轮字幕");
        createCaption(ctx, "bc3", "bcap-b2", 5, tv2,
                        "2026-09-22T06:00:00Z", "2026-09-22T06:20:00Z", List.of("east"))
                .andExpect(status().isOk());
        // 注意 06:00 UTC = 14:00 +08，处于当天节目之外（节目仅 10-11 点），不会阻断；再造节目内黑屏
        String tv3 = ctx.approveText("b3", "节目内字幕");
        createCaption(ctx, "bc4", "bcap-b3", 5, tv3,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:50:00Z", List.of("east"))
                .andExpect(status().isOk());
        createBlackout(ctx, "bk1", "bwin-1", "east",
                        "2026-09-22T02:35:00Z", "2026-09-22T02:45:00Z")
                .andExpect(status().isOk());
        captionPublish(ctx, "bp2", 1, 1, List.of("east"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PUBLISH_BLOCKED"))
                .andExpect(jsonPath("$.blocking[0].region").value("east"))
                .andExpect(jsonPath("$.blocking[0].reason").value("BLACKOUT_CONFLICT"))
                .andExpect(jsonPath("$.blocking[0].windowStart").value("2026-09-22T10:35:00.000+08:00"))
                .andExpect(jsonPath("$.blocking[0].windowEnd").value("2026-09-22T10:45:00.000+08:00"));

        // 黑屏不可变：撤销冲突字幕后重建为 02:30-02:35，其终点恰为黑屏起点（端点相接合法）
        postJson("/api/emergency-captions/" + ctx.cap("bcap-b3") + "/revoke",
                        Map.of("crawlKey", ctx.ck("brv3")))
                .andExpect(status().isOk());
        createCaption(ctx, "bc5", "bcap-b4", 5, tv3,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:35:00Z", List.of("east"))
                .andExpect(status().isOk());
        captionPublish(ctx, "bp3", 1, 1, List.of("east"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(2))
                // 固化决策：bcap-good(02:10-02:20) 与 bcap-b4(02:30-02:35)；
                // 黑屏区间 02:35-02:45 内无字幕选择，端点相接处不阻断
                .andExpect(jsonPath("$.captions.length()").value(2))
                .andExpect(jsonPath("$.captions[1].captionKey").value(ctx.cap("bcap-b4")))
                .andExpect(jsonPath("$.captions[1].end").value("2026-09-22T10:35:00.000+08:00"));
    }

    // ---------- 发布版本/幂等/失败分支 ----------

    @Test
    void publishVersionChecksAndCrawlIdempotency() throws Exception {
        Ctx ctx = newContext();
        prepareProgram(ctx);

        // 404：频道不存在
        mvc.perform(post("/api/channels/" + ctx.prefix + "ghost/drafts/" + DAY + "/caption-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "crawlKey", ctx.ck("px0"), "draftVersion", 1,
                                "expectedPublishedVersion", 0, "regions", List.of("east")))))
                .andExpect(status().isNotFound());
        // 409：草稿版本不符
        captionPublish(ctx, "px1", 9, 0, List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));
        // 409：发布版本不符
        captionPublish(ctx, "px2", 1, 5, List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));

        // 成功发布（无字幕区域，captions 为空列表）
        captionPublish(ctx, "px3", 1, 0, List.of("north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captions.length()").value(0));
        // 同 crawlKey 改参数（区域）：409
        captionPublish(ctx, "px3", 1, 0, List.of("south"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CRAWL_KEY_CONFLICT"));
        // 同 crawlKey 同参数重放：返回首次结果
        captionPublish(ctx, "px3", 1, 0, List.of("north"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    // ---------- 黑屏窗口 ----------

    @Test
    void blackoutWindowLifecycleAndErrors() throws Exception {
        Ctx ctx = newContext();
        createBlackout(ctx, "w1", "win-1", "east",
                        "2026-09-22T02:00:00Z", "2026-09-22T02:30:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blackoutKey").value(ctx.bk("win-1")))
                .andExpect(jsonPath("$.region").value("east"));
        // 同 crawlKey 重放
        createBlackout(ctx, "w1", "win-1", "east",
                        "2026-09-22T02:00:00Z", "2026-09-22T02:30:00Z")
                .andExpect(status().isOk());
        // 同 crawlKey 改参数：409
        createBlackout(ctx, "w1", "win-1", "east",
                        "2026-09-22T02:00:00Z", "2026-09-22T02:31:00Z")
                .andExpect(status().isConflict());
        // 重复键：409
        createBlackout(ctx, "w2", "win-1", "east",
                        "2026-09-22T03:00:00Z", "2026-09-22T03:30:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_BLACKOUT_KEY"));
        // 400：非法窗口
        createBlackout(ctx, "w3", "win-3", "east",
                        "2026-09-22T04:00:00Z", "2026-09-22T04:00:00Z")
                .andExpect(status().isBadRequest());
        // 404：频道不存在 / 明细不存在
        createBlackoutRaw(ctx.ck("w4"), ctx.bk("win-4"), ctx.prefix + "ghost", "east",
                        "2026-09-22T04:00:00Z", "2026-09-22T04:30:00Z")
                .andExpect(status().isNotFound());
        getJson("/api/blackout-windows/" + ctx.prefix + "ghost").andExpect(status().isNotFound());
    }

    // ---------- 并发：同级重叠字幕恰好一条成功 ----------

    @Test
    void concurrentSameRegionSamePriorityOverlapOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        String tv = ctx.approveText("cc", "并发字幕");
        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(
                        post("/api/emergency-captions").contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "crawlKey", ctx.ck("ccc" + i),
                                        "captionKey", ctx.cap("ccap" + i),
                                        "channelId", ctx.channel, "priority", 5,
                                        "textVersionId", tv,
                                        "start", "2026-09-22T02:20:00Z",
                                        "end", "2026-09-22T02:40:00Z",
                                        "regions", List.of("east")))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        int active = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult result = getJson("/api/emergency-captions/" + ctx.cap("ccap" + i)).andReturn();
            if (result.getResponse().getStatus() == 200
                    && readJson(result).get("status").asText().equals("ACTIVE")) {
                active++;
            }
        }
        assertThat(active).isEqualTo(1);
    }

    // ---------- 并发：同 crawlKey 发布重放；异 crawlKey 版本冲突 ----------

    @Test
    void concurrentPublishSameCrawlKeyReplaysAndDifferentKeysConflict() throws Exception {
        Ctx ctx = newContext();
        prepareProgram(ctx);

        // 同 crawlKey 同参数并发：均 200 且返回同一 publicationId
        List<MvcResult> sameKey = runConcurrentlyResults(2, i -> mvc.perform(
                post("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/caption-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "crawlKey", ctx.ck("same"), "draftVersion", 1,
                                "expectedPublishedVersion", 0, "regions", List.of("east")))))
                .andReturn());
        List<Long> samePubIds = new ArrayList<>();
        for (MvcResult result : sameKey) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            samePubIds.add(readJson(result).get("publicationId").asLong());
        }
        assertThat(samePubIds).hasSize(2).containsOnly(samePubIds.get(0));

        // 异 crawlKey 同 expectedPublishedVersion 并发：一胜一 409
        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(
                        post("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/caption-publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "crawlKey", ctx.ck("diff" + i), "draftVersion", 1,
                                        "expectedPublishedVersion", 1, "regions", List.of("east")))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 并发：发布与字幕撤销按提交顺序裁决，快照不受撤销改写 ----------

    @Test
    void concurrentPublishAndRevokeFollowsCommitOrderAndSnapshotStaysFrozen() throws Exception {
        Ctx ctx = newContext();
        prepareProgram(ctx);
        String tv = ctx.approveText("race", "竞态字幕");
        createCaption(ctx, "race-cap", "racecap", 5, tv,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isOk());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publishFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/channels/" + ctx.channel
                                + "/drafts/" + DAY + "/caption-publish")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "crawlKey", ctx.ck("race-pub"), "draftVersion", 1,
                                        "expectedPublishedVersion", 0,
                                        "regions", List.of("east")))))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/emergency-captions/" + ctx.cap("racecap") + "/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("crawlKey", ctx.ck("race-rv")))))
                        .andReturn().getResponse().getStatus();
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int publishStatus = publishFuture.get(30, TimeUnit.SECONDS);
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

            // 两个事务都成功：撤销永远 200；发布无论先后都成功（撤销先提交时该字幕不入选）
            assertThat(revokeStatus).isEqualTo(200);
            assertThat(publishStatus).isEqualTo(200);

            MvcResult pub = getJson("/api/channels/" + ctx.channel
                    + "/drafts/" + DAY + "/latest-publication").andReturn();
            JsonNode pubJson = readJson(pub);
            long publicationId = pubJson.get("publicationId").asLong();
            int frozenCount = pubJson.get("captions").size();
            assertThat(frozenCount).isIn(0, 1);

            // 02:30 的回执严格按发布快照：发布先提交则仍确认到已撤销字幕；撤销先提交则无字幕
            String expectedReason = frozenCount == 1 ? "CAPTION_CONFIRMED" : "NO_CAPTION";
            confirm(ctx, "race-rcpt", "east", "2026-09-22T02:30:00Z")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.publicationId").value(publicationId))
                    .andExpect(jsonPath("$.reason").value(expectedReason));
            if (frozenCount == 1) {
                confirm(ctx, "race-rcpt2", "east", "2026-09-22T02:30:00Z")
                        .andExpect(jsonPath("$.captionKey").value(ctx.cap("racecap")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 失败不占 crawlKey / 回执无节目单 ----------

    @Test
    void failedCreateDoesNotConsumeCrawlKeyAndReceiptRequiresSchedule() throws Exception {
        Ctx ctx = newContext();
        String tv = ctx.approveText("fk", "文本");
        createCaption(ctx, "fk-1", "fkcap-1", 5, tv,
                        "2026-09-22T02:20:00Z", "2026-09-22T02:40:00Z", List.of("east"))
                .andExpect(status().isOk());
        // 同 crawlKey 首次因窗口重叠 409 失败
        createCaption(ctx, "fk-retry", "fkcap-2", 5, tv,
                        "2026-09-22T02:30:00Z", "2026-09-22T02:50:00Z", List.of("east"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CAPTION_INTERVAL_CONFLICT"));
        // 失败回滚不占 crawlKey：同 crawlKey 改窗口后成功（参数不同也不报冲突）
        createCaption(ctx, "fk-retry", "fkcap-2", 5, tv,
                        "2026-09-22T05:00:00Z", "2026-09-22T05:10:00Z", List.of("east"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("fkcap-2")));
        // 失败也不占 captionKey
        getJson("/api/emergency-captions/" + ctx.cap("fkcap-2"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 无已发布节目单时回执 422，原因可区分
        confirm(ctx, "fk-rcpt", "east", "2026-09-22T02:30:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PLAYOUT_NO_SCHEDULE"));
        // 失败不占 crawlKey：发布节目后同 crawlKey 可成功确认（fkcap-1 在 02:30 覆盖）
        prepareProgram(ctx);
        captionPublish(ctx, "fk-pub", 1, 0, List.of("east")).andExpect(status().isOk());
        confirm(ctx, "fk-rcpt", "east", "2026-09-22T02:30:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("CAPTION_CONFIRMED"))
                .andExpect(jsonPath("$.captionKey").value(ctx.cap("fkcap-1")));
    }

    // ---------- 测试辅助 ----------

    private final class Ctx {
        private final String prefix = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String channel;
        private String fallbackAsset;
        private String movieAsset;
        private long movieGrant;

        private String ck(String shortKey) {
            return prefix + "ck-" + shortKey;
        }

        private String tv(String shortKey) {
            return prefix + "tv-" + shortKey;
        }

        private String cap(String shortKey) {
            return prefix + "cap-" + shortKey;
        }

        private String bk(String shortKey) {
            return prefix + "bk-" + shortKey;
        }

        /** 创建 PENDING 文本版本并返回其 versionId。 */
        private String newText(String shortKey, String content) throws Exception {
            String versionId = tv(shortKey);
            postJson("/api/caption-texts", Map.of(
                            "crawlKey", ck("txt-" + shortKey), "versionId", versionId,
                            "content", content))
                    .andExpect(status().isOk());
            return versionId;
        }

        /** 创建文本版本并审核通过，返回 versionId。 */
        private String approveText(String shortKey, String content) throws Exception {
            String versionId = newText(shortKey, content);
            postJson("/api/caption-texts/" + versionId + "/review", Map.of(
                            "crawlKey", ck("rev-" + shortKey), "decision", "APPROVED"))
                    .andExpect(status().isOk());
            return versionId;
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

    /** 仅创建一段 10:00-11:00 (+08) 的 movie 节目草稿 v1；发布由字幕感知发布完成。 */
    private void prepareProgram(Ctx ctx) throws Exception {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", ctx.prefix + "seg-1");
        segment.put("assetId", ctx.movieAsset);
        segment.put("start", SEG_START);
        segment.put("end", SEG_END);
        Map<String, Object> draftBody = new LinkedHashMap<>();
        draftBody.put("requestId", ctx.ck("draft-1"));
        draftBody.put("expectedDraftVersion", 0);
        draftBody.put("segments", List.of(segment));
        mvc.perform(put("/api/channels/" + ctx.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draftBody)))
                .andExpect(status().isOk());
    }

    private ResultActions createCaption(Ctx ctx, String crawlShort, String captionShort, int priority,
                                        String textVersionId, String start, String end,
                                        List<String> regions) throws Exception {
        return createCaptionRaw(ctx.ck(crawlShort), ctx.cap(captionShort), ctx.channel, priority,
                textVersionId, start, end, regions);
    }

    private ResultActions createCaptionRaw(String crawlKey, String captionKey, String channelId,
                                           int priority, String textVersionId, String start,
                                           String end, List<String> regions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("crawlKey", crawlKey);
        body.put("captionKey", captionKey);
        body.put("channelId", channelId);
        body.put("priority", priority);
        body.put("textVersionId", textVersionId);
        body.put("start", start);
        body.put("end", end);
        body.put("regions", regions);
        return postJson("/api/emergency-captions", body);
    }

    private ResultActions createBlackout(Ctx ctx, String crawlShort, String blackoutShort,
                                         String region, String start, String end) throws Exception {
        return createBlackoutRaw(ctx.ck(crawlShort), ctx.bk(blackoutShort), ctx.channel,
                region, start, end);
    }

    private ResultActions createBlackoutRaw(String crawlKey, String blackoutKey, String channelId,
                                            String region, String start, String end) throws Exception {
        return postJson("/api/blackout-windows", Map.of(
                "crawlKey", crawlKey, "blackoutKey", blackoutKey, "channelId", channelId,
                "region", region, "start", start, "end", end));
    }

    private ResultActions captionPublish(Ctx ctx, String crawlShort, long draftVersion,
                                         long expectedPublishedVersion, List<String> regions)
            throws Exception {
        return postJson("/api/channels/" + ctx.channel + "/drafts/" + DAY + "/caption-publish",
                Map.of("crawlKey", ctx.ck(crawlShort), "draftVersion", draftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion, "regions", regions));
    }

    private ResultActions decision(Ctx ctx, String region, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + ctx.channel + "/caption-decision")
                .param("region", region).param("at", at));
    }

    private ResultActions confirm(Ctx ctx, String crawlShort, String region, String at)
            throws Exception {
        return postJson("/api/playout-receipts", Map.of(
                "crawlKey", ctx.ck(crawlShort), "channelId", ctx.channel,
                "region", region, "at", at));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<JsonNode> collect(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private interface ThrowingSupplier {
        Integer get(int index) throws Exception;
    }

    private interface ThrowingResultSupplier {
        MvcResult get(int index) throws Exception;
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
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return statuses;
    }

    private List<MvcResult> runConcurrentlyResults(int threads, ThrowingResultSupplier action)
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
