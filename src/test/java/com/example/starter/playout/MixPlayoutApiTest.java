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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交叉淡化混音 API 端到端测试：合成时间线、增益决策、版本门禁、授权整组回退、
 * 紧急插播优先、幂等重放与并发。运行环境为 H2（MODE=MySQL）内存库，唯一约束、
 * 行锁与事务回滚均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MixPlayoutApiTest {

    private static final String DAY = "2026-10-10";
    private static final String START = "2026-10-10T10:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-10-10T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-10-11T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：A/B 各 20 秒、淡化 4 秒，B 在第 16 秒开始，总时长 36 秒 ----------

    @Test
    void mixDraftPublishAndOverlapGains() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        long grantA = createGrant(channel, p + "a");
        long grantB = createGrant(channel, p + "b");

        // 登记混音草稿：A → B，淡化 4 秒
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op-1", 0, START,
                List.of(mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 4000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.items[0].start").value("2026-10-10T10:00:00.000+08:00"))
                .andExpect(jsonPath("$.items[0].end").value("2026-10-10T10:00:20.000+08:00"))
                .andExpect(jsonPath("$.items[1].start").value("2026-10-10T10:00:16.000+08:00"))
                .andExpect(jsonPath("$.items[1].end").value("2026-10-10T10:00:36.000+08:00"))
                .andExpect(jsonPath("$.items[1].fadeInMs").value(4000));

        // 未发布 → 保底
        mixPlayout(channel, "2026-10-10T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));

        publishMix(channel, Map.of("requestId", "req-" + p + "p1", "operator", "op-1",
                        "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andExpect(jsonPath("$.draftVersion").value(1));

        // 非重叠区：单条目，增益 1.0
        mixPlayout(channel, "2026-10-10T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.components[0].assetId").value(p + "a"))
                .andExpect(jsonPath("$.components[0].grantId").value(grantA))
                .andExpect(jsonPath("$.components[0].gain").value(1.0));

        // 重叠区左端（第 16 秒）：u=0，前增益 1.0、后增益 0.0
        mixPlayout(channel, "2026-10-10T10:00:16.000+08:00")
                .andExpect(jsonPath("$.source").value("MIX"))
                .andExpect(jsonPath("$.components[0].assetId").value(p + "a"))
                .andExpect(jsonPath("$.components[0].gain").value(1.0))
                .andExpect(jsonPath("$.components[1].assetId").value(p + "b"))
                .andExpect(jsonPath("$.components[1].gain").value(0.0));

        // 第 17 秒：u=0.25
        mixPlayout(channel, "2026-10-10T10:00:17.000+08:00")
                .andExpect(jsonPath("$.source").value("MIX"))
                .andExpect(jsonPath("$.components[0].gain").value(0.75))
                .andExpect(jsonPath("$.components[1].gain").value(0.25));

        // 第 18 秒：两增益各 0.5，返回两素材与各自授权
        mixPlayout(channel, "2026-10-10T10:00:18.000+08:00")
                .andExpect(jsonPath("$.source").value("MIX"))
                .andExpect(jsonPath("$.components[0].assetId").value(p + "a"))
                .andExpect(jsonPath("$.components[0].grantId").value(grantA))
                .andExpect(jsonPath("$.components[0].gain").value(0.5))
                .andExpect(jsonPath("$.components[1].assetId").value(p + "b"))
                .andExpect(jsonPath("$.components[1].grantId").value(grantB))
                .andExpect(jsonPath("$.components[1].gain").value(0.5));

        // 第 19 秒：u=0.75
        mixPlayout(channel, "2026-10-10T10:00:19.000+08:00")
                .andExpect(jsonPath("$.source").value("MIX"))
                .andExpect(jsonPath("$.components[0].gain").value(0.25))
                .andExpect(jsonPath("$.components[1].gain").value(0.75));

        // 重叠区右端（第 20 秒）：仅后素材
        mixPlayout(channel, "2026-10-10T10:00:20.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.components[0].assetId").value(p + "b"))
                .andExpect(jsonPath("$.components[0].gain").value(1.0));

        // 总时长 36 秒：第 36 秒起为空档 → 保底
        mixPlayout(channel, "2026-10-10T10:00:36.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GAP"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));
    }

    // ---------- 混音草稿校验规则 ----------

    @Test
    void mixDraftValidationRules() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createGrant(channel, p + "a");

        // 422：淡化超过相邻素材时长（B 仅 3 秒，淡化 4 秒）
        createAsset(p + "short", 3000);
        putMixDraft(channel, replaceMixBody("req-" + p + "fd", "op", 0, START,
                List.of(mixItem(p + "i1", p + "a", 0), mixItem(p + "i2", p + "short", 4000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_FADE_EXCEEDS_DURATION"));

        // 422：相邻淡化区间叠加 → 三条素材同时播出（2+2 > 3）
        createAsset(p + "c", 20000);
        putMixDraft(channel, replaceMixBody("req-" + p + "to", "op", 0, START,
                List.of(mixItem(p + "i1", p + "a", 0), mixItem(p + "i2", p + "short", 2000),
                        mixItem(p + "i3", p + "c", 2000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_TRIPLE_OVERLAP"));

        // 422：素材时长非整秒
        createAsset(p + "frac", 1500);
        putMixDraft(channel, replaceMixBody("req-" + p + "fr", "op", 0, START,
                List.of(mixItem(p + "i1", p + "frac", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_DURATION_NOT_WHOLE_SECONDS"));

        // 422：淡化超出 0～10 秒
        putMixDraft(channel, replaceMixBody("req-" + p + "fr2", "op", 0, START,
                List.of(mixItem(p + "i1", p + "a", 0), mixItem(p + "i2", p + "c", 11000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_FADE_OUT_OF_RANGE"));

        // 400：首条目淡化不为 0
        putMixDraft(channel, replaceMixBody("req-" + p + "ff", "op", 0, START,
                List.of(mixItem(p + "i1", p + "a", 1000))))
                .andExpect(status().isBadRequest());

        // 422：混音起点不在业务日内
        putMixDraft(channel, replaceMixBody("req-" + p + "cd", "op", 0,
                "2026-10-09T23:00:00.000+08:00", List.of(mixItem(p + "i1", p + "a", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_CROSSES_DAY"));

        // 422：整体播出区间跨业务日
        putMixDraft(channel, replaceMixBody("req-" + p + "cd2", "op", 0,
                "2026-10-10T23:59:50.000+08:00", List.of(mixItem(p + "i1", p + "a", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_CROSSES_DAY"));

        // 404：素材不存在
        putMixDraft(channel, replaceMixBody("req-" + p + "na", "op", 0, START,
                List.of(mixItem(p + "i1", p + "ghost", 0))))
                .andExpect(status().isNotFound());

        // 422：素材无授权
        createAsset(p + "nogrant", 20000);
        putMixDraft(channel, replaceMixBody("req-" + p + "ng", "op", 0, START,
                List.of(mixItem(p + "i1", p + "nogrant", 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));

        // 422：授权只覆盖非重叠部分，未覆盖完整实际播放区间（重叠段 10:00:16-10:00:20 不在授权内）
        createAsset(p + "partial", 20000);
        createGrant(channel, p + "partial",
                "2026-10-10T10:00:20.000+08:00", "2026-10-10T11:00:00.000+08:00");
        putMixDraft(channel, replaceMixBody("req-" + p + "pc", "op", 0, START,
                List.of(mixItem(p + "i1", p + "a", 0), mixItem(p + "i2", p + "partial", 4000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_COVERING_GRANT"));
    }

    // ---------- 版本门禁与幂等 ----------

    @Test
    void mixVersionGatesAndIdempotency() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        createGrant(channel, p + "a");
        createGrant(channel, p + "b");
        List<Map<String, Object>> items = List.of(
                mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 4000));

        // 409：首次创建版本必须为 0
        putMixDraft(channel, replaceMixBody("req-" + p + "v0", "op", 1, START, items))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));

        Map<String, Object> body = replaceMixBody("req-" + p + "d1", "op-1", 0, START, items);
        MvcResult first = putMixDraft(channel, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        // 同 requestId 同参数重放：返回首次完整响应，版本不重复递增
        putMixDraft(channel, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(content().string(first.getResponse().getContentAsString()));
        // 同 requestId 改操作者：409
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op-2", 0, START, items))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改参数（淡化时长）：409
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op-1", 0, START,
                List.of(mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 2000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 409：重复以 0 创建
        putMixDraft(channel, replaceMixBody("req-" + p + "d2", "op", 0, START, items))
                .andExpect(status().isConflict());

        // 失败不占键：三重叠失败后同 requestId 成功
        createAsset(p + "short", 3000);
        createGrant(channel, p + "short");
        putMixDraft(channel, replaceMixBody("req-" + p + "retry", "op", 1, START,
                List.of(mixItem(p + "x1", p + "a", 0), mixItem(p + "x2", p + "short", 2000),
                        mixItem(p + "x3", p + "b", 2000))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MIX_TRIPLE_OVERLAP"));
        putMixDraft(channel, replaceMixBody("req-" + p + "retry", "op", 1, START, items))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 发布版本门禁
        publishMix(channel, Map.of("requestId", "req-" + p + "pb0", "operator", "op",
                        "draftVersion", 9, "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DRAFT_VERSION_CONFLICT"));
        Map<String, Object> pub = Map.of("requestId", "req-" + p + "pb1", "operator", "op",
                "draftVersion", 2, "expectedPublishedVersion", 0);
        MvcResult published = publishMix(channel, pub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1))
                .andReturn();
        long publicationId = readJson(published).get("publicationId").asLong();
        // 幂等重放：返回首次完整响应（同一 publicationId）
        publishMix(channel, pub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationId").value(publicationId));
        // 同 requestId 改期望版本：409
        publishMix(channel, Map.of("requestId", "req-" + p + "pb1", "operator", "op",
                        "draftVersion", 2, "expectedPublishedVersion", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 409：发布版本冲突
        publishMix(channel, Map.of("requestId", "req-" + p + "pb2", "operator", "op",
                        "draftVersion", 2, "expectedPublishedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUBLISHED_VERSION_CONFLICT"));
        // 404：该业务日混音草稿不存在
        publishMix(channel, "2026-10-11", Map.of("requestId", "req-" + p + "pb3", "operator", "op",
                        "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布授权不足：422 整次不发布，失败不占键 ----------

    @Test
    void publishRejectedWhenGrantInsufficientAndRetryAfterRegrant() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        createGrant(channel, p + "a");
        long grantB = createGrant(channel, p + "b");
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op", 0, START,
                List.of(mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 4000))))
                .andExpect(status().isOk());

        // 撤销 B 的授权 → 发布 422，整次不发布
        postJson("/api/grants/" + grantB + "/revoke", Map.of("requestId", "req-" + p + "rv"))
                .andExpect(status().isOk());
        Map<String, Object> pub = Map.of("requestId", "req-" + p + "pb", "operator", "op",
                "draftVersion", 1, "expectedPublishedVersion", 0);
        publishMix(channel, pub)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));
        // 整次不发布：无快照，决策仍为无已发布编排
        mixPlayout(channel, "2026-10-10T10:00:18.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"));

        // 失败不占键：补齐授权后同 requestId 同参数发布成功
        createGrant(channel, p + "b");
        publishMix(channel, pub)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedVersion").value(1));
    }

    // ---------- 授权撤销：当前决策整组回退保底，不能只播另一条 ----------

    @Test
    void revokeFallsBackWholeMixGroup() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        long grantA = createGrant(channel, p + "a");
        long grantB = createGrant(channel, p + "b");
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op", 0, START,
                List.of(mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 4000))))
                .andExpect(status().isOk());
        publishMix(channel, Map.of("requestId", "req-" + p + "pb", "operator", "op",
                        "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        // 撤销 B 的授权：重叠区整组回退保底（不能只播 A），B 独占区同样回退
        postJson("/api/grants/" + grantB + "/revoke", Map.of("requestId", "req-" + p + "rv-b"))
                .andExpect(status().isOk());
        mixPlayout(channel, "2026-10-10T10:00:18.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"))
                .andExpect(jsonPath("$.publicationId").isNumber());
        mixPlayout(channel, "2026-10-10T10:00:25.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"));
        // A 独占区授权仍有效：正常播出
        mixPlayout(channel, "2026-10-10T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.components[0].assetId").value(p + "a"));

        // 再撤销 A：A 独占区也回退
        postJson("/api/grants/" + grantA + "/revoke", Map.of("requestId", "req-" + p + "rv-a"))
                .andExpect(status().isOk());
        mixPlayout(channel, "2026-10-10T10:00:10.000+08:00")
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(p + "fallback"));
    }

    // ---------- 紧急插播优先替代整组混音 ----------

    @Test
    void emergencyOverrideReplacesWholeMixGroup() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        createGrant(channel, p + "a");
        createGrant(channel, p + "b");
        putMixDraft(channel, replaceMixBody("req-" + p + "d1", "op", 0, START,
                List.of(mixItem(p + "m-a", p + "a", 0), mixItem(p + "m-b", p + "b", 4000))))
                .andExpect(status().isOk());
        publishMix(channel, Map.of("requestId", "req-" + p + "pb", "operator", "op",
                        "draftVersion", 1, "expectedPublishedVersion", 0))
                .andExpect(status().isOk());

        createAsset(p + "news", 60000);
        long grantNews = createGrant(channel, p + "news");
        postJson("/api/emergency-overrides", Map.of(
                        "requestId", "req-" + p + "ov", "overrideKey", p + "ov-1",
                        "channelId", channel, "assetId", p + "news", "grantId", grantNews,
                        "priority", 5,
                        "start", "2026-10-10T10:00:17.000+08:00",
                        "end", "2026-10-10T10:00:19.000+08:00"))
                .andExpect(status().isOk());

        // 插播期间：整组混音被替代
        mixPlayout(channel, "2026-10-10T10:00:18.000+08:00")
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(p + "news"))
                .andExpect(jsonPath("$.overrideKey").value(p + "ov-1"));
        // 插播结束（左闭右开）：恢复混音决策
        mixPlayout(channel, "2026-10-10T10:00:19.000+08:00")
                .andExpect(jsonPath("$.source").value("MIX"))
                .andExpect(jsonPath("$.components[0].gain").value(0.25))
                .andExpect(jsonPath("$.components[1].gain").value(0.75));
    }

    // ---------- 并发：同一版本仅一方成功 ----------

    @Test
    void concurrentMixDraftReplaceAndPublishOnlyOneWins() throws Exception {
        String p = prefix();
        String channel = newChannel(p);
        createAsset(p + "a", 20000);
        createAsset(p + "b", 20000);
        createGrant(channel, p + "a");
        createGrant(channel, p + "b");

        // 并发首建混音草稿（expectedDraftVersion=0）：仅一方成功
        List<Integer> drafts = runConcurrently(2, i -> {
            Map<String, Object> body = replaceMixBody("req-" + p + "c" + i, "op", 0, START,
                    List.of(mixItem(p + "ma" + i, p + "a", 0), mixItem(p + "mb" + i, p + "b", 4000)));
            return mvc.perform(put("/api/channels/" + channel + "/mix-drafts/" + DAY)
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(drafts).containsExactlyInAnyOrder(200, 409);

        // 并发发布同一版本：仅一方成功
        List<Integer> publishes = runConcurrently(2, i -> {
            Map<String, Object> body = Map.of("requestId", "req-" + p + "pb" + i, "operator", "op",
                    "draftVersion", 1, "expectedPublishedVersion", 0);
            return mvc.perform(post("/api/channels/" + channel + "/mix-drafts/" + DAY + "/publish")
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(publishes).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 测试辅助 ----------

    /** 创建保底素材与频道，返回频道 ID。 */
    private String newChannel(String p) throws Exception {
        postJson("/api/assets", Map.of("id", p + "fallback", "durationMs", 30000))
                .andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", p + "ch", "fallbackAssetId", p + "fallback"))
                .andExpect(status().isOk());
        return p + "ch";
    }

    private void createAsset(String id, long durationMs) throws Exception {
        postJson("/api/assets", Map.of("id", id, "durationMs", durationMs))
                .andExpect(status().isOk());
    }

    private long createGrant(String channel, String asset) throws Exception {
        return createGrant(channel, asset, GRANT_FROM, GRANT_TO);
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset, "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> mixItem(String id, String assetId, long fadeInMs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("assetId", assetId);
        item.put("fadeInMs", fadeInMs);
        return item;
    }

    private static Map<String, Object> replaceMixBody(String requestId, String operator,
                                                      long expectedVersion, String startAt,
                                                      List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("expectedDraftVersion", expectedVersion);
        body.put("startAt", startAt);
        body.put("items", items);
        return body;
    }

    private ResultActions putMixDraft(String channel, Object body) throws Exception {
        return mvc.perform(put("/api/channels/" + channel + "/mix-drafts/" + DAY)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions publishMix(String channel, Object body) throws Exception {
        return publishMix(channel, DAY, body);
    }

    private ResultActions publishMix(String channel, String day, Object body) throws Exception {
        return mvc.perform(post("/api/channels/" + channel + "/mix-drafts/" + day + "/publish")
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions mixPlayout(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/mix-playout").param("at", at));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String prefix() {
        return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
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
