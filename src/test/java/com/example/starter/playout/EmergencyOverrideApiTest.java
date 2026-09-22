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
 * 紧急插播 API 端到端测试：覆盖创建/取消/明细主流程、参数与授权失败回滚、幂等边界、
 * 优先级回落语义，以及同级重叠并发、取消并发、创建与授权撤销并发的提交顺序语义。
 * 运行环境为 H2（MySQL 兼容模式）真实内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmergencyOverrideApiTest {

    private static final String DAY = "2026-09-22";
    private static final String T_10 = "2026-09-22T10:00:00.000+08:00";
    private static final String T_10_10 = "2026-09-22T10:10:00.000+08:00";
    private static final String T_10_15 = "2026-09-22T10:15:00.000+08:00";
    private static final String T_10_20 = "2026-09-22T10:20:00.000+08:00";
    private static final String T_10_30 = "2026-09-22T10:30:00.000+08:00";
    private static final String T_10_30_001 = "2026-09-22T10:30:00.001+08:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+08:00";
    private static final String T_12 = "2026-09-22T12:00:00.000+08:00";
    private static final String GRANT_FROM = "2026-09-22T00:00:00.000+08:00";
    private static final String GRANT_TO = "2026-09-23T00:00:00.000+08:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程：创建、明细、命中播出、取消 ----------

    @Test
    void createQueryAndCancelLifecycle() throws Exception {
        Setup s = Setup.create(this);
        String key = s.p + "ov-1";

        // 创建即 ACTIVE，响应回显毫秒 ISO 时间与优先级
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c1", key,
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideKey").value(key))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.assetId").value(s.a))
                .andExpect(jsonPath("$.grantId").value(s.ga))
                .andExpect(jsonPath("$.start").value(T_10))
                .andExpect(jsonPath("$.end").value(T_10_30))
                .andExpect(jsonPath("$.cancelRequestId").isEmpty())
                .andExpect(jsonPath("$.cancelledAt").isEmpty());

        // 区间起点命中：无任何已发布编排也直接 EMERGENCY，返回素材与 overrideKey
        playout(s.channel, T_10)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(s.a))
                .andExpect(jsonPath("$.overrideKey").value(key))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andExpect(jsonPath("$.publicationId").isEmpty())
                .andExpect(jsonPath("$.segmentId").isEmpty());

        // 到结束时刻不再命中（左闭右开）：无发布编排时回到保底
        playout(s.channel, T_10_30)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("NO_PUBLISHED_SCHEDULE"))
                .andExpect(jsonPath("$.overrideKey").isEmpty());

        // 取消主流程
        postJson("/api/emergency-overrides/" + key + "/cancel",
                        Map.of("requestId", "req-" + s.p + "x1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value("req-" + s.p + "x1"))
                .andExpect(jsonPath("$.cancelledAt").isString());

        // 明细保留取消情况与原授权关联
        getJson("/api/emergency-overrides/" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.grantId").value(s.ga));

        // 取消提交后释放冲突范围：同键不可复用，但同区间可新建其它插播
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("FALLBACK"));
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c2", s.p + "ov-2",
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 404：明细不存在
        getJson("/api/emergency-overrides/" + s.p + "ghost")
                .andExpect(status().isNotFound());
    }

    // ---------- 参数非法 400 ----------

    @Test
    void invalidParametersReturn400() throws Exception {
        Setup s = Setup.create(this);

        // 优先级越界（0 与 10）由 Bean Validation 拒绝
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "p0", s.p + "k0",
                        s.channel, s.a, s.ga, 0, T_10, T_10_30))
                .andExpect(status().isBadRequest());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "p10", s.p + "k10",
                        s.channel, s.a, s.ga, 10, T_10, T_10_30))
                .andExpect(status().isBadRequest());
        // 缺少 requestId
        postJson("/api/emergency-overrides", overrideBody(null, s.p + "kn",
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isBadRequest());
        // 结束不大于开始
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "inv", s.p + "ki",
                        s.channel, s.a, s.ga, 5, T_10_30, T_10))
                .andExpect(status().isBadRequest());
        // 时长超过 30 分钟（30 分钟 + 1 毫秒）
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "long", s.p + "kl",
                        s.channel, s.a, s.ga, 5, T_10, T_10_30_001))
                .andExpect(status().isBadRequest());
        // 跨业务日
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "day", s.p + "kd",
                        s.channel, s.a, s.ga, 5,
                        "2026-09-22T23:50:00.000+08:00",
                        "2026-09-23T00:10:00.000+08:00"))
                .andExpect(status().isBadRequest());
        // 30 分钟整为合法边界，先建一条供后续相邻测试复用
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "edge", s.p + "kedge",
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk());
        // 取消缺少 requestId
        postJson("/api/emergency-overrides/" + s.p + "kedge/cancel", Map.of())
                .andExpect(status().isBadRequest());
    }

    // ---------- 资源不存在 404 与授权不满足 422 ----------

    @Test
    void notFoundAndGrantValidationFailures() throws Exception {
        Setup s = Setup.create(this);

        // 404：频道 / 素材 / 授权不存在
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "nc", s.p + "k1",
                        s.p + "ghost", s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isNotFound());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "na", s.p + "k2",
                        s.channel, s.p + "ghost", s.ga, 5, T_10, T_10_30))
                .andExpect(status().isNotFound());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "ng", s.p + "k3",
                        s.channel, s.a, 999999L, 5, T_10, T_10_30))
                .andExpect(status().isNotFound());

        // 422：保底素材不得作为插播素材
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "fb", s.p + "k4",
                        s.channel, s.fb, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FALLBACK_ASSET_NOT_GRANTABLE"));

        // 422：授权与素材不匹配（ga 属于素材 a，却带素材 b）
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "mm", s.p + "k5",
                        s.channel, s.b, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_MISMATCH"));

        // 422：授权属于其它频道
        postJson("/api/assets", Map.of("id", s.p + "fb2", "durationMs", 30000)).andExpect(status().isOk());
        postJson("/api/channels", Map.of("id", s.p + "ch2", "fallbackAssetId", s.p + "fb2"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "mc", s.p + "k6",
                        s.p + "ch2", s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_MISMATCH"));

        // 422：授权窗口不完整覆盖（窄授权 11:00-12:00，插播 10:00-10:30）
        long narrow = createGrant(s.channel, s.a, T_11, T_12);
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "ncv", s.p + "k7",
                        s.channel, s.a, narrow, 5, T_10, T_10_30))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_NOT_COVERING"));

        // 422：授权已撤销（撤销先提交，创建被拒）
        postJson("/api/grants/" + s.gc + "/revoke", Map.of("requestId", "req-" + s.p + "rv"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "rvx", s.p + "k8",
                        s.channel, s.c, s.gc, 5, T_10, T_10_30))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GRANT_INVALID"));

        // 失败整体回滚：未写入插播明细，且 requestId 不被占用（同 requestId 改合法参数可成功）
        getJson("/api/emergency-overrides/" + s.p + "k8").andExpect(status().isNotFound());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "rvx", s.p + "k9",
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrideKey").value(s.p + "k9"));

        // 授权撤销不改变插播自身状态、不换绑：k9 仍 ACTIVE 且关联 ga，但播出查询排除
        postJson("/api/grants/" + s.ga + "/revoke", Map.of("requestId", "req-" + s.p + "rv2"))
                .andExpect(status().isOk());
        getJson("/api/emergency-overrides/" + s.p + "k9")
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.grantId").value(s.ga));
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.overrideKey").isEmpty());
    }

    // ---------- 同级重叠 / 相邻 / 跨优先级 ----------

    @Test
    void samePriorityOverlapAdjacentAndDifferentPriority() throws Exception {
        Setup s = Setup.create(this);

        // priority 5：[10:00,10:20)
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "a", s.p + "A",
                        s.channel, s.a, s.ga, 5, T_10, T_10_20))
                .andExpect(status().isOk());
        // 相邻 [10:20,10:30) 合法
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "b", s.p + "B",
                        s.channel, s.b, s.gb, 5, T_10_20, T_10_30))
                .andExpect(status().isOk());
        // 与 A 同级重叠：409
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c", s.p + "C",
                        s.channel, s.c, s.gc, 5, T_10_10, T_10_30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_OVERLAP"));
        // 与 B 同级重叠（起点落在 B 区间内）：409
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c2", s.p + "C2",
                        s.channel, s.c, s.gc, 5, T_10_15, T_10_20))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_OVERLAP"));
        // 不同优先级允许重叠：priority 4 覆盖整个窗口
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "d", s.p + "D",
                        s.channel, s.c, s.gc, 4, T_10, T_10_30))
                .andExpect(status().isOk());

        // 重复 overrideKey（任意参数）：409
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "dup", s.p + "A",
                        s.channel, s.a, s.ga, 6, T_10, T_10_20))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_KEY_EXISTS"));

        // 取消 A 释放冲突范围后，原来与 A 重叠的 C 可以创建
        postJson("/api/emergency-overrides/" + s.p + "A/cancel",
                        Map.of("requestId", "req-" + s.p + "x-a"))
                .andExpect(status().isOk());
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c3", s.p + "C",
                        s.channel, s.c, s.gc, 5, T_10_10, T_10_20))
                .andExpect(status().isOk());

        // 取消不存在的插播：404
        postJson("/api/emergency-overrides/" + s.p + "ghost/cancel",
                        Map.of("requestId", "req-" + s.p + "x-g"))
                .andExpect(status().isNotFound());
        // 重复取消：409
        postJson("/api/emergency-overrides/" + s.p + "A/cancel",
                        Map.of("requestId", "req-" + s.p + "x-a2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_ALREADY_CANCELLED"));
    }

    // ---------- 优先级抢占与授权失效回落 ----------

    @Test
    void priorityPreemptionFailoverAndProgramFallback() throws Exception {
        Setup s = Setup.create(this);
        // 发布节目 10:00-11:00，素材 a（插播窗口内本来应播节目）
        publishProgram(s, T_10, T_11, s.a);

        // 低优先级（数字大）插播素材 b
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "low", s.p + "LOW",
                        s.channel, s.b, s.gb, 7, T_10, T_10_30))
                .andExpect(status().isOk());
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(s.b))
                .andExpect(jsonPath("$.overrideKey").value(s.p + "LOW"));

        // 高优先级（数字小）插播素材 c，抢占低优先级
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "hi", s.p + "HIGH",
                        s.channel, s.c, s.gc, 1, T_10, T_10_30))
                .andExpect(status().isOk());
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(s.c))
                .andExpect(jsonPath("$.overrideKey").value(s.p + "HIGH"));

        // 高优先级授权撤销：不影响其 ACTIVE 状态，但播出落到仍有效的低优先级插播
        postJson("/api/grants/" + s.gc + "/revoke", Map.of("requestId", "req-" + s.p + "rvc"))
                .andExpect(status().isOk());
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("EMERGENCY"))
                .andExpect(jsonPath("$.assetId").value(s.b))
                .andExpect(jsonPath("$.overrideKey").value(s.p + "LOW"));
        getJson("/api/emergency-overrides/" + s.p + "HIGH")
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 低优先级授权也撤销：无插播候选，沿用原节目
        postJson("/api/grants/" + s.gb + "/revoke", Map.of("requestId", "req-" + s.p + "rvb"))
                .andExpect(status().isOk());
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.assetId").value(s.a))
                .andExpect(jsonPath("$.overrideKey").isEmpty());

        // 节目授权再撤销：回到保底
        postJson("/api/grants/" + s.ga + "/revoke", Map.of("requestId", "req-" + s.p + "rva"))
                .andExpect(status().isOk());
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.assetId").value(s.fb));
    }

    // ---------- 创建幂等：重放不改结果、改参 409、不复活已取消插播 ----------

    @Test
    void createIdempotencyAndNoResurrect() throws Exception {
        Setup s = Setup.create(this);
        String key = s.p + "idem";
        Map<String, Object> body = overrideBody("req-" + s.p + "idem", key,
                s.channel, s.a, s.ga, 5, T_10, T_10_30);

        postJson("/api/emergency-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 同 requestId 同参重放：首次结果
        postJson("/api/emergency-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 同 requestId 改参（优先级变化）：409
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "idem", key,
                        s.channel, s.a, s.ga, 6, T_10, T_10_30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 改操作（拿去取消）：409
        postJson("/api/emergency-overrides/" + s.p + "other/cancel",
                        Map.of("requestId", "req-" + s.p + "idem"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 取消插播
        postJson("/api/emergency-overrides/" + key + "/cancel",
                        Map.of("requestId", "req-" + s.p + "cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 重放原创建请求返回首次结果快照，但不得复活：明细仍 CANCELLED、播出不命中
        postJson("/api/emergency-overrides", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        getJson("/api/emergency-overrides/" + key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.overrideKey").isEmpty());

        // 新 requestId 复用同 overrideKey：409，键不随取消而释放
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "again", key,
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_KEY_EXISTS"));
    }

    // ---------- 取消幂等 ----------

    @Test
    void cancelIdempotencyReplayAndConflict() throws Exception {
        Setup s = Setup.create(this);
        String key = s.p + "cx";
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c", key,
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk());

        Map<String, Object> cancelBody = Map.of("requestId", "req-" + s.p + "x");
        postJson("/api/emergency-overrides/" + key + "/cancel", cancelBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 同 requestId 重放：返回首次取消结果
        postJson("/api/emergency-overrides/" + key + "/cancel", cancelBody)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestId").value("req-" + s.p + "x"));
        // 新 requestId 重复取消：409
        postJson("/api/emergency-overrides/" + key + "/cancel",
                        Map.of("requestId", "req-" + s.p + "x2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OVERRIDE_ALREADY_CANCELLED"));
        // 同 requestId 改作用对象：409
        postJson("/api/emergency-overrides/" + s.p + "other/cancel", cancelBody)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 并发：同级重叠最多一条成功 ----------

    @Test
    void concurrentSamePriorityOverlapOnlyOneWins() throws Exception {
        Setup s = Setup.create(this);
        List<Integer> statuses = runConcurrently(2, i -> postJson("/api/emergency-overrides",
                overrideBody("req-" + s.p + "cc" + i, s.p + "cc" + i,
                        s.channel, s.a, s.ga, 5, T_10, T_10_20))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        // 失败事务回滚：两个键中恰好一个存在（ACTIVE），另一个 404
        int cc0Status = getJsonRaw("/api/emergency-overrides/" + s.p + "cc0");
        int cc1Status = getJsonRaw("/api/emergency-overrides/" + s.p + "cc1");
        assertThat(List.of(cc0Status, cc1Status)).containsExactlyInAnyOrder(200, 404);
    }

    // ---------- 并发：同一插播重复取消只有一条成功 ----------

    @Test
    void concurrentCancelOnlyOneWins() throws Exception {
        Setup s = Setup.create(this);
        String key = s.p + "vx";
        postJson("/api/emergency-overrides", overrideBody("req-" + s.p + "c", key,
                        s.channel, s.a, s.ga, 5, T_10, T_10_30))
                .andExpect(status().isOk());

        List<Integer> statuses = runConcurrently(2, i -> postJson(
                "/api/emergency-overrides/" + key + "/cancel",
                Map.of("requestId", "req-" + s.p + "vx" + i))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        getJson("/api/emergency-overrides/" + key)
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- 并发：创建与授权撤销按事务提交顺序处理 ----------

    @Test
    void concurrentCreateAndRevokeCommitOrdering() throws Exception {
        Setup s = Setup.create(this);
        String key = s.p + "race";
        publishProgram(s, T_10, T_11, s.a);

        List<Integer> statuses = runConcurrently(2, i -> {
            if (i == 0) {
                return postJson("/api/grants/" + s.ga + "/revoke",
                        Map.of("requestId", "req-" + s.p + "race-rv"))
                        .andReturn().getResponse().getStatus();
            }
            return postJson("/api/emergency-overrides", overrideBody(
                    "req-" + s.p + "race-cr", key, s.channel, s.a, s.ga, 5, T_10, T_10_30))
                    .andReturn().getResponse().getStatus();
        });
        int revokeStatus = statuses.get(0);
        int createStatus = statuses.get(1);
        assertThat(revokeStatus).isEqualTo(200);
        assertThat(createStatus).isIn(200, 422);

        if (createStatus == 422) {
            // 撤销先提交：创建被拒，插播不存在
            getJson("/api/emergency-overrides/" + key).andExpect(status().isNotFound());
        } else {
            // 创建先提交：插播保留 ACTIVE 与原授权关联，但授权撤销后播出查询必须排除它
            getJson("/api/emergency-overrides/" + key)
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.grantId").value(s.ga));
        }
        // 无论提交顺序如何，授权最终已撤销，插播（若存在）不得命中播出
        playout(s.channel, T_10_15)
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.reason").value("GRANT_REVOKED"))
                .andExpect(jsonPath("$.overrideKey").isEmpty());
    }

    // ---------- 测试夹具与辅助 ----------

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private int getJsonRaw(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getStatus();
    }

    private ResultActions playout(String channel, String at) throws Exception {
        return mvc.perform(get("/api/channels/" + channel + "/playout").param("at", at));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private long createGrant(String channel, String asset, String from, String to) throws Exception {
        MvcResult result = postJson("/api/grants", Map.of(
                        "channelId", channel, "assetId", asset,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** 发布频道当日一个节目片段（素材 asset，授权用该素材的全天授权），用于回落链路验证。 */
    private void publishProgram(Setup s, String start, String end, String asset) throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("id", s.p + "prog");
        seg.put("assetId", asset);
        seg.put("start", start);
        seg.put("end", end);
        segments.add(seg);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("requestId", "req-" + s.p + "draft");
        draft.put("expectedDraftVersion", 0);
        draft.put("segments", segments);
        mvc.perform(put("/api/channels/" + s.channel + "/drafts/" + DAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draft)))
                .andExpect(status().isOk());
        postJson("/api/channels/" + s.channel + "/drafts/" + DAY + "/publish",
                        Map.of("requestId", "req-" + s.p + "pub", "draftVersion", 1,
                                "expectedPublishedVersion", 0))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> overrideBody(String requestId, String key, String channel,
                                                     String asset, Long grantId, int priority,
                                                     String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("overrideKey", key);
        body.put("channelId", channel);
        body.put("assetId", asset);
        body.put("grantId", grantId);
        body.put("priority", priority);
        body.put("start", start);
        body.put("end", end);
        return body;
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

    /**
     * 测试夹具：保底素材 fb、普通素材 a/b/c、频道 ch 及三条全天授权 ga/gb/gc。
     */
    private record Setup(String p, String channel, String fb, String a, String b, String c,
                         long ga, long gb, long gc) {

        static Setup create(EmergencyOverrideApiTest t) throws Exception {
            String p = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
            String fb = p + "fb";
            String a = p + "a";
            String b = p + "b";
            String c = p + "c";
            String channel = p + "ch";
            t.postJson("/api/assets", Map.of("id", fb, "durationMs", 30000)).andExpect(status().isOk());
            for (String asset : List.of(a, b, c)) {
                t.postJson("/api/assets", Map.of("id", asset, "durationMs", 3600000))
                        .andExpect(status().isOk());
            }
            t.postJson("/api/channels", Map.of("id", channel, "fallbackAssetId", fb))
                    .andExpect(status().isOk());
            long ga = t.createGrant(channel, a, GRANT_FROM, GRANT_TO);
            long gb = t.createGrant(channel, b, GRANT_FROM, GRANT_TO);
            long gc = t.createGrant(channel, c, GRANT_FROM, GRANT_TO);
            return new Setup(p, channel, fb, a, b, c, ga, gb, gc);
        }
    }
}
