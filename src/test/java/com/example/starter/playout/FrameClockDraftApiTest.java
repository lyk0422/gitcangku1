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
 * 帧时钟草稿 API 端到端测试：30000/1001 等帧率下按有理数时刻校验与查询，
 * 毫秒输出仅显示取整。运行环境为 H2（MODE=MySQL）内存库，覆盖主流程、失败分支、
 * 幂等与并发边界；所有时刻均为固定合成值，不依赖系统时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrameClockDraftApiTest {

    private static final String DAY = "2026-10-05";
    private static final String ORIGIN = "2026-10-05T00:00:00.000+08:00";
    private static final String NEXT_DAY = "2026-10-06T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：30000 帧恰 1001 秒，相邻片段无缝衔接 ----------

    @Test
    void frameDraftExactSecondsAndSeamlessAdjacency() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d1", 0, 30000, 1001, List.of(
                                frameSegment(p + "seg-a", p + "movieA", 0, 30000),
                                frameSegment(p + "seg-b", p + "movieB", 30000, 60000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeBase").value("FRAME"))
                .andExpect(jsonPath("$.frameRate.num").value(30000))
                .andExpect(jsonPath("$.frameRate.den").value(1001))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].startFrame").value(0))
                .andExpect(jsonPath("$.segments[0].endFrame").value(30000))
                .andExpect(jsonPath("$.segments[0].start").value(ORIGIN))
                // 30000 帧 × 1001 / 30000 = 恰 1001 秒（00:16:41），不积累逐帧舍入误差
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-05T00:16:41.000+08:00"))
                .andExpect(jsonPath("$.segments[1].start").value("2026-10-05T00:16:41.000+08:00"))
                .andExpect(jsonPath("$.segments[1].end").value("2026-10-05T00:33:22.000+08:00"));

        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 第 30000 帧开始前 1 毫秒仍属片段 A
        getJson("/api/channels/" + channel + "/playout", "2026-10-05T00:16:40.999+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movieA"))
                .andExpect(jsonPath("$.segmentId").value(p + "seg-a"));
        // 第 30000 帧开始（恰 1001 秒）无缝衔接片段 B，不积累误差
        getJson("/api/channels/" + channel + "/playout", "2026-10-05T00:16:41.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movieB"))
                .andExpect(jsonPath("$.segmentId").value(p + "seg-b"));
    }

    // ---------- 显示取整只是展示：查询与校验走真实有理数时刻 ----------

    @Test
    void displayFloorsButQueryUsesRationalAxis() throws Exception {
        String p = prefix();
        // 素材 34ms：恰能覆盖 1 帧真实时长 33.3666...ms
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "clip", "durationMs", 34))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of(
                        "channelId", p + "ch", "assetId", p + "clip",
                        "validFrom", ORIGIN, "validTo", "2026-10-05T00:00:00.034+08:00"))
                .andExpect(status().isOk());

        // 单帧片段 [0,1)：显示结束向下取整为 33ms
        putJson("/api/channels/" + p + "ch/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d1", 0, 30000, 1001,
                                List.of(frameSegment(p + "seg-1", p + "clip", 0, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-05T00:00:00.033+08:00"));
        postJson("/api/channels/" + p + "ch/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 33ms 时刻真实上仍在第 1 帧内（33 < 33.3666...），命中片段
        getJson("/api/channels/" + p + "ch/playout", "2026-10-05T00:00:00.033+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "clip"));
        // 34ms 时刻已超过真实结束，进入空档；若按显示取整的 [0,33) 判定会错误命中
        getJson("/api/channels/" + p + "ch/playout", "2026-10-05T00:00:00.034+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"));
    }

    // ---------- 帧率合法性与坐标混用 ----------

    @Test
    void frameRateAndCoordinateValidation() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;

        // 400：不支持的帧率 30/1
        putJson(url, frameDraftBody("req-" + p + "-r1", 0, 30, 1,
                        List.of(frameSegment(p + "s1", p + "movieA", 0, 30))))
                .andExpect(status().isBadRequest());
        // 400：帧时钟草稿携带毫秒时刻（混两种坐标）
        Map<String, Object> mixed = frameDraftBody("req-" + p + "-r2", 0, 30000, 1001,
                List.of(Map.of("id", p + "s1", "assetId", p + "movieA",
                        "start", ORIGIN, "end", "2026-10-05T01:00:00.000+08:00")));
        putJson(url, mixed).andExpect(status().isBadRequest());
        // 400：毫秒草稿携带帧号（混两种坐标）
        Map<String, Object> msMixed = new LinkedHashMap<>();
        msMixed.put("requestId", "req-" + p + "-r3");
        msMixed.put("expectedDraftVersion", 0);
        msMixed.put("segments", List.of(Map.of("id", p + "s1", "assetId", p + "movieA",
                "start", ORIGIN, "end", "2026-10-05T01:00:00.000+08:00",
                "startFrame", 0, "endFrame", 24)));
        putJson(url, msMixed).andExpect(status().isBadRequest());
        // 400：负帧号
        putJson(url, frameDraftBody("req-" + p + "-r4", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "movieA", -1, 10))))
                .andExpect(status().isBadRequest());
        // 400：结束帧不大于开始帧
        putJson(url, frameDraftBody("req-" + p + "-r5", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "movieA", 10, 10))))
                .andExpect(status().isBadRequest());

        // 建立 FRAME 草稿后，同一份草稿不能换成毫秒坐标或另一种帧率
        putJson(url, frameDraftBody("req-" + p + "-ok", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "movieA", 0, 30000))))
                .andExpect(status().isOk());
        Map<String, Object> msBody = new LinkedHashMap<>();
        msBody.put("requestId", "req-" + p + "-r6");
        msBody.put("expectedDraftVersion", 1);
        msBody.put("segments", List.of(Map.of("id", p + "s2", "assetId", p + "movieA",
                "start", ORIGIN, "end", "2026-10-05T01:00:00.000+08:00")));
        putJson(url, msBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_TIME_BASE_CONFLICT"));
        putJson(url, frameDraftBody("req-" + p + "-r7", 1, 25, 1,
                        List.of(frameSegment(p + "s2", p + "movieA", 0, 25))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_TIME_BASE_CONFLICT"));

        // 24/1 与 25/1 帧率可建：24fps 第 24 帧、25fps 第 25 帧均恰为 1 秒
        String p24 = prefix();
        String ch24 = newChannel(p24);
        putJson("/api/channels/" + ch24 + "/drafts/" + DAY,
                        frameDraftBody("req-" + p24 + "-r8", 0, 24, 1,
                                List.of(frameSegment(p24 + "s1", p24 + "movieA", 0, 24))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-05T00:00:01.000+08:00"));
        String p25 = prefix();
        String ch25 = newChannel(p25);
        putJson("/api/channels/" + ch25 + "/drafts/" + DAY,
                        frameDraftBody("req-" + p25 + "-r9", 0, 25, 1,
                                List.of(frameSegment(p25 + "s1", p25 + "movieA", 0, 25))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-10-05T00:00:01.000+08:00"));
    }

    // ---------- 帧区间边界：重叠、端点相接与一天上限 ----------

    @Test
    void frameOverlapAdjacencyAndDayBound() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;

        // 422：帧区间重叠
        putJson(url, frameDraftBody("req-" + p + "-ov", 0, 30000, 1001, List.of(
                        frameSegment(p + "s1", p + "movieA", 0, 100),
                        frameSegment(p + "s2", p + "movieA", 50, 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_OVERLAP"));

        // 端点相接合法：[0,100) 与 [100,200)
        putJson(url, frameDraftBody("req-" + p + "-adj", 0, 30000, 1001, List.of(
                        frameSegment(p + "s1", p + "movieA", 0, 100),
                        frameSegment(p + "s2", p + "movieA", 100, 200))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 422：有理数结束超过一天（30000/1001 上限为第 2589410 帧）
        putJson(url, frameDraftBody("req-" + p + "-day", 1, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "movieA", 0, 2589411))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_CROSSES_DAY"));

        // 边界值 2589410 帧合法（有理数结束恰不超过一天）
        postJson("/api/assets", Map.of("id", p + "allday", "durationMs", 86400000))
                .andExpect(status().isOk());
        createGrant(channel, p + "allday", ORIGIN, NEXT_DAY);
        putJson(url, frameDraftBody("req-" + p + "-max", 1, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "allday", 0, 2589410))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 授权覆盖与素材时长按真实有理数时刻判定 ----------

    @Test
    void grantCoverageAndAssetDurationUseRationalTime() throws Exception {
        String p = prefix();
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        // 34ms 素材：可覆盖 1 帧；33ms 素材：不足 1 帧真实时长
        postJson("/api/assets", Map.of("id", p + "clip34", "durationMs", 34))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "clip33", "durationMs", 33))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        // 33ms 授权：显示取整与单帧片段显示结束相同，但真实时刻短不足一帧
        createGrant(p + "ch", p + "clip34", ORIGIN, "2026-10-05T00:00:00.033+08:00");
        // 34ms 授权：真实覆盖 1 帧
        createGrant(p + "ch", p + "clip33", ORIGIN, "2026-10-05T00:00:00.034+08:00");
        String url = "/api/channels/" + p + "ch/drafts/" + DAY;

        // 422：短不足一帧的授权不被显示取整放过
        putJson(url, frameDraftBody("req-" + p + "-g1", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "clip34", 0, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 422：素材真实时长不足一帧（33ms < 33.3666...ms），授权覆盖也不能放行
        putJson(url, frameDraftBody("req-" + p + "-g2", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "clip33", 0, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_EXCEEDS_ASSET_DURATION"));

        // 对照：34ms 素材 + 34ms 授权可覆盖单帧片段
        createGrant(p + "ch", p + "clip34", ORIGIN, "2026-10-05T00:00:00.034+08:00");
        putJson(url, frameDraftBody("req-" + p + "-g3", 0, 30000, 1001,
                        List.of(frameSegment(p + "s1", p + "clip34", 0, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 幂等：同键同参重放、异参 409、失败不占键 ----------

    @Test
    void frameDraftIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;
        Map<String, Object> body = frameDraftBody("req-" + p + "-idem", 0, 30000, 1001,
                List.of(frameSegment(p + "seg-a", p + "movieA", 0, 30000)));

        // 首次成功
        MvcResult first = putJson(url, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        // 同 requestId 同参数重放：返回首次完整响应，不重复增版本
        MvcResult replay = putJson(url, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 不同帧号（30000 → 30001）：409，不按显示毫秒归并
        putJson(url, frameDraftBody("req-" + p + "-idem", 0, 30000, 1001,
                        List.of(frameSegment(p + "seg-a", p + "movieA", 0, 30001))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以重叠片段失败，再用同 requestId 成功
        putJson(url, frameDraftBody("req-" + p + "-retry", 1, 30000, 1001, List.of(
                        frameSegment(p + "a", p + "movieA", 0, 100),
                        frameSegment(p + "b", p + "movieA", 50, 150))))
                .andExpect(status().isUnprocessableEntity());
        putJson(url, frameDraftBody("req-" + p + "-retry", 1, 30000, 1001,
                        List.of(frameSegment(p + "ok", p + "movieA", 0, 150))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 发布冻结帧号/帧率/原点，撤销与发布按版本竞争 ----------

    @Test
    void publishFreezesFramesAndRevokeCompetes() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;

        putJson(url, frameDraftBody("req-" + p + "-d1", 0, 30000, 1001,
                        List.of(frameSegment(p + "seg-a", p + "movieA", 0, 30000))))
                .andExpect(status().isOk());
        postJson(url + "/publish", Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 草稿替换为另一素材后，已发布快照仍冻结原帧号/帧率/原点
        putJson(url, frameDraftBody("req-" + p + "-d2", 1, 30000, 1001,
                        List.of(frameSegment(p + "seg-b", p + "movieB", 0, 30000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        getJson("/api/channels/" + channel + "/playout", "2026-10-05T00:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movieA"))
                .andExpect(jsonPath("$.segmentId").value(p + "seg-a"));

        // 409：发布版本竞争（已发布过版本 1）
        postJson(url + "/publish", Map.of("requestId", "req-" + p + "-p2", "draftVersion", 2,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));

        // 帧快照上授权撤销的实时应用：历史快照不改写，查询应用撤销结果
        String p2 = prefix();
        postJson("/api/assets", Map.of("id", p2 + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p2 + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p2 + "ch", "fallbackAssetId", p2 + "fallback"))
                .andExpect(status().isOk());
        long grant = createGrant(p2 + "ch", p2 + "movie", ORIGIN, NEXT_DAY);
        String url2 = "/api/channels/" + p2 + "ch/drafts/" + DAY;
        putJson(url2, frameDraftBody("req-" + p2 + "-d1", 0, 30000, 1001,
                        List.of(frameSegment(p2 + "seg", p2 + "movie", 0, 30000))))
                .andExpect(status().isOk());
        postJson(url2 + "/publish", Map.of("requestId", "req-" + p2 + "-p1", "draftVersion", 1,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isOk());
        getJson("/api/channels/" + p2 + "ch/playout", "2026-10-05T00:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"));
        postJson("/api/grants/" + grant + "/revoke", Map.of("requestId", "req-" + p2 + "-rv"))
                .andExpect(status().isOk());
        getJson("/api/channels/" + p2 + "ch/playout", "2026-10-05T00:10:00.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p2 + "fallback"));

        // 撤销先提交 → 发布因授权失效被拒绝（帧坐标同样按提交顺序竞争）
        String p3 = prefix();
        postJson("/api/assets", Map.of("id", p3 + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p3 + "movie", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p3 + "ch", "fallbackAssetId", p3 + "fallback"))
                .andExpect(status().isOk());
        long grant3 = createGrant(p3 + "ch", p3 + "movie", ORIGIN, NEXT_DAY);
        String url3 = "/api/channels/" + p3 + "ch/drafts/" + DAY;
        putJson(url3, frameDraftBody("req-" + p3 + "-d1", 0, 30000, 1001,
                        List.of(frameSegment(p3 + "seg", p3 + "movie", 0, 30000))))
                .andExpect(status().isOk());
        postJson("/api/grants/" + grant3 + "/revoke", Map.of("requestId", "req-" + p3 + "-rv"))
                .andExpect(status().isOk());
        postJson(url3 + "/publish", Map.of("requestId", "req-" + p3 + "-p1", "draftVersion", 1,
                        "expectedPublishedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));
    }

    // ---------- 并发：同一版本仅一方成功 ----------

    @Test
    void concurrentFrameDraftAndPublishOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String url = "/api/channels/" + channel + "/drafts/" + DAY;

        // 并发首建帧草稿（expectedDraftVersion=0）：恰一方成功
        List<Integer> firstRound = runConcurrently(2, i ->
                mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                                .content(json(frameDraftBody("req-" + p + "-c" + i, 0, 30000, 1001,
                                        List.of(frameSegment(p + "seg-" + i, p + "movieA", 0, 30000))))))
                        .andReturn().getResponse().getStatus());
        assertThat(firstRound).containsExactlyInAnyOrder(200, 409);

        // 并发发布同一期望版本：恰一方成功
        List<Integer> secondRound = runConcurrently(2, i ->
                mvc.perform(post(url + "/publish").contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("requestId", "req-" + p + "-pc" + i,
                                        "draftVersion", 1, "expectedPublishedVersion", 0))))
                        .andReturn().getResponse().getStatus());
        assertThat(secondRound).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 测试辅助 ----------

    /** 创建频道、两个节目素材与各自的全天授权，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movieA", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "movieB", "durationMs", 3600000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        createGrant(p + "ch", p + "movieA", ORIGIN, NEXT_DAY);
        createGrant(p + "ch", p + "movieB", ORIGIN, NEXT_DAY);
        return p + "ch";
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> frameSegment(String id, String assetId,
                                                    long startFrame, long endFrame) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("startFrame", startFrame);
        segment.put("endFrame", endFrame);
        return segment;
    }

    private static Map<String, Object> frameDraftBody(String requestId, long expectedVersion,
                                                      int num, int den,
                                                      List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("frameRate", Map.of("num", num, "den", den));
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
        return "f" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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
