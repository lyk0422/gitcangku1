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
 * 帧时钟草稿端到端测试：有理数真实时刻、显示毫秒向下取整、发布冻结、
 * 有理数轴播出查询、幂等与并发。运行环境为 H2（MODE=MySQL）内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrameClockApiTest {

    private static final String DAY = "2026-09-22";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：30000 帧 @30000/1001 恰为 1001 秒，相邻片段无缝 ----------

    @Test
    void frameDraftMainFlowExact1001SecondsAndSeamlessAdjacent() throws Exception {
        String p = prefix();
        String channel = newChannel(p);

        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-f1", 0, 30000, 1001, List.of(
                                frameSegment(p + "s1", p + "movie", 0, 30000),
                                frameSegment(p + "s2", p + "movie", 30000, 60000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.frameRate.numerator").value(30000))
                .andExpect(jsonPath("$.frameRate.denominator").value(1001))
                .andExpect(jsonPath("$.segments[0].startFrame").value(0))
                .andExpect(jsonPath("$.segments[0].endFrame").value(30000))
                .andExpect(jsonPath("$.segments[0].start").value("2026-09-22T00:00:00.000+08:00"))
                // 30000 帧 @30000/1001 恰为 1001 秒；显示毫秒向下取整
                .andExpect(jsonPath("$.segments[0].end").value("2026-09-22T00:16:41.000+08:00"))
                // 相邻第 30000 帧开始的新片段无缝衔接，不积累误差
                .andExpect(jsonPath("$.segments[1].start").value("2026-09-22T00:16:41.000+08:00"))
                .andExpect(jsonPath("$.segments[1].end").value("2026-09-22T00:33:22.000+08:00"));
    }

    // ---------- 帧草稿参数校验 ----------

    @Test
    void frameDraftValidationBranches() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        // 全天时长素材，用于“结束恰为一天”边界
        postJson("/api/assets", Map.of("id", p + "big", "durationMs", 86400000L))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of("channelId", channel, "assetId", p + "big",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk());

        // 400：不支持的帧率
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-rate", 0, 30, 1,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 30))))
                .andExpect(status().isBadRequest());
        // 400：帧草稿中片段携带毫秒时间（混两种坐标）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-mix1", 0, 24, 1,
                                List.of(msSegment(p + "s1", p + "movie",
                                        "2026-09-22T01:00:00.000+08:00",
                                        "2026-09-22T02:00:00.000+08:00"))))
                .andExpect(status().isBadRequest());
        // 400：毫秒草稿中片段携带帧号（混两种坐标）
        Map<String, Object> msBody = new LinkedHashMap<>();
        msBody.put("requestId", "req-" + p + "-mix2");
        msBody.put("expectedDraftVersion", 0);
        msBody.put("segments", List.of(frameSegment(p + "s1", p + "movie", 0, 24)));
        putJson("/api/channels/" + channel + "/drafts/" + DAY, msBody)
                .andExpect(status().isBadRequest());
        // 400：负帧号
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-neg", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "movie", -1, 24))))
                .andExpect(status().isBadRequest());
        // 400：结束帧不大于开始帧
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-ord", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "movie", 24, 24))))
                .andExpect(status().isBadRequest());
        // 422：有理数结束超过一天（24fps 一天为 2073600 帧）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-day", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "big", 0, 2073601))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_CROSSES_DAY"));
        // 边界：结束恰为一天合法
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-dayok", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "big", 0, 2073600))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-09-23T00:00:00.000+08:00"));
        // 422：帧区间重叠
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-ov", 1, 25, 1, List.of(
                                frameSegment(p + "s1", p + "movie", 0, 100),
                                frameSegment(p + "s2", p + "movie", 50, 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_OVERLAP"));
        // 端点相接合法
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-adj", 1, 25, 1, List.of(
                                frameSegment(p + "s1", p + "movie", 0, 100),
                                frameSegment(p + "s2", p + "movie", 100, 200))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 授权覆盖按真实有理数时刻判定，短不足一帧的授权不被显示取整放过 ----------

    @Test
    void grantCoverageUsesRationalTimeNotDisplayFloor() throws Exception {
        String p = prefix();
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/assets", Map.of("id", p + "clip", "durationMs", 1000000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        // 授权只到 00:00:00.041：24fps 第 1 帧真实结束为 41.667ms，显示取整为 41ms
        postJson("/api/grants", Map.of("channelId", p + "ch", "assetId", p + "clip",
                        "validFrom", "2026-09-22T00:00:00.000+08:00",
                        "validTo", "2026-09-22T00:00:00.041+08:00"))
                .andExpect(status().isOk());

        // 422：短不足一帧的授权不能覆盖 [0,1) 帧片段
        putJson("/api/channels/" + p + "ch/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-g1", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "clip", 0, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 授权延长到 00:00:00.042（>= 真实结束 41.667ms）后覆盖成立
        postJson("/api/grants", Map.of("channelId", p + "ch", "assetId", p + "clip",
                        "validFrom", "2026-09-22T00:00:00.000+08:00",
                        "validTo", "2026-09-22T00:00:00.042+08:00"))
                .andExpect(status().isOk());
        putJson("/api/channels/" + p + "ch/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-g2", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "clip", 0, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].end").value("2026-09-22T00:00:00.041+08:00"));
    }

    // ---------- 素材时长校验按真实有理数时刻 ----------

    @Test
    void assetDurationCheckedRationally() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        // 41ms 素材：显示取整与 24fps 单帧相同，但真实 41.667ms 超时长
        postJson("/api/assets", Map.of("id", p + "a41", "durationMs", 41))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of("channelId", channel, "assetId", p + "a41",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d41", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "a41", 0, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_EXCEEDS_ASSET_DURATION"));

        // 42ms 素材可以容纳真实 41.667ms 的单帧
        postJson("/api/assets", Map.of("id", p + "a42", "durationMs", 42))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of("channelId", channel, "assetId", p + "a42",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d42", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "a42", 0, 1))))
                .andExpect(status().isOk());

        // 24 帧恰为 1000ms：等于素材时长合法，25 帧（1041.667ms）超出
        postJson("/api/assets", Map.of("id", p + "a1000", "durationMs", 1000))
                .andExpect(status().isOk());
        postJson("/api/grants", Map.of("channelId", channel, "assetId", p + "a1000",
                        "validFrom", GRANT_FROM, "validTo", GRANT_TO))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d24", 1, 24, 1,
                                List.of(frameSegment(p + "s1", p + "a1000", 0, 24))))
                .andExpect(status().isOk());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d25", 2, 24, 1,
                                List.of(frameSegment(p + "s1", p + "a1000", 0, 25))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SEGMENT_EXCEEDS_ASSET_DURATION"));
    }

    // ---------- 发布冻结帧号/帧率/原点，播出查询按有理数轴左闭右开 ----------

    @Test
    void publishFreezesFramesAndPlayoutUsesRationalAxis() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d1", 0, 30000, 1001,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 30000))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));

        // 真实结束恰为 1001 秒：1000999ms 处仍在片段内，1001000ms 处已结束（左闭右开）
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:16:40.999+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"))
                .andExpect(jsonPath("$.segmentId").value(p + "s1"));
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:16:41.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"));
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:00:00.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"));
    }

    @Test
    void playoutRationalAxisSubFrameBoundary() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        // 24fps 片段 [0,1)：真实结束 41.667ms，显示取整为 41ms
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d1", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 1))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 41ms 处真实时刻仍在片段内（41 < 41.667），显示取整不该把它排除
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:00:00.041+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(p + "movie"));
        // 42ms 处已超出真实结束
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:00:00.042+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"));
    }

    // ---------- 帧草稿幂等：同键同参重放，异参 409，失败不占键 ----------

    @Test
    void frameDraftIdempotencyAndCoordinateNotConfusedByDisplayMs() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        Map<String, Object> body = frameDraftBody("req-" + p + "-idem", 0, 30000, 1001,
                List.of(frameSegment(p + "s1", p + "movie", 0, 30000)));

        putJson("/api/channels/" + channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 同 requestId 同参数重放：返回首次完整响应，不重复增版本
        putJson("/api/channels/" + channel + "/drafts/" + DAY, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.segments[0].endFrame").value(30000));
        // 同 requestId 改帧号：409
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-idem", 0, 30000, 1001,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 30001))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改坐标：毫秒片段显示值与帧片段相同，也不能当作同参
        Map<String, Object> msBody = new LinkedHashMap<>();
        msBody.put("requestId", "req-" + p + "-idem");
        msBody.put("expectedDraftVersion", 0);
        msBody.put("segments", List.of(msSegment(p + "s1", p + "movie",
                "2026-09-22T00:00:00.000+08:00", "2026-09-22T00:16:41.000+08:00")));
        putJson("/api/channels/" + channel + "/drafts/" + DAY, msBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以重叠片段失败，再用同 requestId 成功
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-retry", 1, 25, 1, List.of(
                                frameSegment(p + "a", p + "movie", 0, 100),
                                frameSegment(p + "b", p + "movie", 50, 150))))
                .andExpect(status().isUnprocessableEntity());
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-retry", 1, 25, 1,
                                List.of(frameSegment(p + "ok", p + "movie", 0, 100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 版本竞争：帧草稿替换与发布遵循原版本规则 ----------

    @Test
    void frameDraftVersionConflictKeepsOriginal() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-v1", 0, 24, 1,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 240))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 409：版本不符，且不改写原草稿
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-v9", 5, 24, 1,
                                List.of(frameSegment(p + "s2", p + "movie", 0, 240))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));
        // 正确版本替换成功（版本只递增一次，证明失败请求未污染）
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-v2", 1, 25, 1,
                                List.of(frameSegment(p + "s2", p + "movie", 0, 250))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.frameRate.numerator").value(25));

        // 发布遵循草稿版本竞争：旧草稿版本 409，当前版本成功
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p0", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 2,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.draftVersion").value(2));
    }

    // ---------- 授权撤销后帧快照播出查询回退 ----------

    @Test
    void revokeAfterFramePublishFallsBack() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        long grantId = lastGrantId;
        putJson("/api/channels/" + channel + "/drafts/" + DAY,
                        frameDraftBody("req-" + p + "-d1", 0, 30000, 1001,
                                List.of(frameSegment(p + "s1", p + "movie", 0, 30000))))
                .andExpect(status().isOk());
        postJson("/api/channels/" + channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + p + "-p1", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:08:20.500+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"));
        postJson("/api/grants/" + grantId + "/revoke", Map.of("requestId", "req-" + p + "-rv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        // 快照不被改写，但播出查询应用撤销结果
        getJson("/api/channels/" + channel + "/playout", "2026-09-22T00:08:20.500+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"))
                .andExpect(jsonPath("$.segmentId").value(p + "s1"));
    }

    // ---------- 并发：帧草稿同版本替换与同版本发布仅一方成功 ----------

    @Test
    void concurrentFrameDraftReplaceAndPublishOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        String draftUrl = "/api/channels/" + channel + "/drafts/" + DAY;

        List<Integer> draftRaces = runConcurrently(2, i -> {
            Map<String, Object> body = frameDraftBody("req-" + p + "-c" + i, 0, 24, 1,
                    List.of(frameSegment(p + "seg-" + i, p + "movie", 0, 240)));
            return mvc.perform(put(draftUrl).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(draftRaces).containsExactlyInAnyOrder(200, 409);

        String publishUrl = draftUrl + "/publish";
        List<Integer> publishRaces = runConcurrently(2, i -> {
            Map<String, Object> body = Map.of("requestId", "req-" + p + "-pc" + i,
                    "draftVersion", 1, "expectedPublishedVersion", 0);
            return mvc.perform(post(publishUrl).contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(publishRaces).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 测试辅助 ----------

    private long lastGrantId;

    /** 创建保底/电影素材、频道与一条全天授权，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
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

    private static Map<String, Object> rate(int numerator, int denominator) {
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("numerator", numerator);
        rate.put("denominator", denominator);
        return rate;
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

    private static Map<String, Object> msSegment(String id, String assetId,
                                                 String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("id", id);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private static Map<String, Object> frameDraftBody(String requestId, long expectedVersion,
                                                      int rateNum, int rateDen,
                                                      List<Map<String, Object>> segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("segments", segments);
        body.put("frameRate", rate(rateNum, rateDen));
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
