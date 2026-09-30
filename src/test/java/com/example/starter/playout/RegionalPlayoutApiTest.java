package com.example.starter.playout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 区域切换播出编排 API 端到端测试：多区域整单主流程、授权窗口/时段重叠 422 整单回滚、
 * 左闭右开相邻、requestId 幂等、同区域并发最多一方成功、授权撤销只影响之后且历史固化、
 * 占位状态按可控时钟派生。全部结论在真实 H2（MODE=MySQL）内存库上验证。
 *
 * <p>注意：H2 内存库与 playout_request 幂等表在同 JVM 全部测试间共享，因此区域、素材、
 * requestId 一律带随机前缀，全局视图断言按本测试素材前缀过滤。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RegionalPlayoutApiTest.FixedClockConfig.class)
class RegionalPlayoutApiTest {

    // 输入用 UTC 绝对时刻；授权/占位均按 UTC 判重，与展示时区无关。
    private static final String T_10Z = "2026-09-22T10:00:00.000Z";
    private static final String T_1030Z = "2026-09-22T10:30:00.000Z";
    private static final String T_1045Z = "2026-09-22T10:45:00.000Z";
    private static final String T_1050Z = "2026-09-22T10:50:00.000Z";
    private static final String T_1059Z = "2026-09-22T10:59:00.000Z";
    private static final String T_11Z = "2026-09-22T11:00:00.000Z";
    private static final String T_1101Z = "2026-09-22T11:01:00.000Z";
    private static final String T_1130Z = "2026-09-22T11:30:00.000Z";
    private static final String T_1145Z = "2026-09-22T11:45:00.000Z";
    private static final String T_12Z = "2026-09-22T12:00:00.000Z";
    private static final String T_13Z = "2026-09-22T13:00:00.000Z";
    private static final String T_1330Z = "2026-09-22T13:30:00.000Z";
    private static final String T_14Z = "2026-09-22T14:00:00.000Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    /** 固定可控时钟：初始在所有授权窗口之前，测试中推进以验证 SCHEDULED/AIRED 边界。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-22T09:00:00Z"));
        }
    }

    // ---------- 主流程：两区域同时段提交、视图隔离、状态按时钟派生 ----------

    @Test
    void multiRegionMainFlowViewsAndClockDerivedStatus() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_11Z);
        ctx.createRegionGrant("B", T_11Z, T_12Z);

        // 素材 S：A 10:00-11:00、B 11:00-12:00，两段一次提交成功
        postJson("/api/regional/allocations", allocateBody(ctx, "main",
                segment(ctx, "A", T_10Z, T_11Z), segment(ctx, "B", T_11Z, T_12Z)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(ctx.rq("main")))
                .andExpect(jsonPath("$.segments[0].regionId").value(ctx.region("A")))
                .andExpect(jsonPath("$.segments[0].grantVersion").value(1))
                .andExpect(jsonPath("$.segments[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.segments[1].regionId").value(ctx.region("B")))
                .andExpect(jsonPath("$.segments[1].status").value("SCHEDULED"));

        // 编排员视图：本测试两个区域占位都在（库可能含其他测试数据，按素材前缀过滤）
        assertThat(allocationCount(ctx)).isEqualTo(2);
        assertThat(regionIdsOfMine(ctx)).containsExactlyInAnyOrder(ctx.region("A"), ctx.region("B"));

        // 区域播控方视图：A 只看得到本区域时段
        getRegionAllocations(ctx, "A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].regionId").value(ctx.region("A")))
                .andExpect(jsonPath("$.segments[0].assetId").value(ctx.asset));
        getRegionAllocations(ctx, "B")
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].regionId").value(ctx.region("B")));
        // 404：未知区域视图
        getJson("/api/regional/regions/" + ctx.prefix + "ghost/allocations")
                .andExpect(status().isNotFound());

        // 时钟推进到 11:00（A 段结束端点，左闭右开）：A AIRED，B 仍 SCHEDULED
        clock.setInstant(Instant.parse("2026-09-22T11:00:00Z"));
        getRegionAllocations(ctx, "A")
                .andExpect(jsonPath("$.segments[0].status").value("AIRED"));
        getRegionAllocations(ctx, "B")
                .andExpect(jsonPath("$.segments[0].status").value("SCHEDULED"));
        // 12:00 本测试两段均 AIRED
        clock.setInstant(Instant.parse("2026-09-22T12:00:00Z"));
        List<JsonNode> mine = myAllocations(ctx);
        assertThat(mine).hasSize(2);
        assertThat(mine).allSatisfy(node ->
                assertThat(node.get("status").asText()).isEqualTo("AIRED"));
    }

    // ---------- 题干场景：A 再申请重叠窗口，整单失败且新区域不留占位 ----------

    @Test
    void overlappingSecondApplicationFailsWholeOrderWithoutPartialPlaceholders() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_11Z);
        ctx.createRegionGrant("B", T_11Z, T_12Z);
        ctx.createRegionGrant("C", T_11Z, T_12Z);

        postJson("/api/regional/allocations", allocateBody(ctx, "first",
                segment(ctx, "A", T_10Z, T_11Z), segment(ctx, "B", T_11Z, T_12Z)))
                .andExpect(status().isOk());

        // A 再申请 10:30（与已提交占位重叠），同一单里 C 段本身合法：整单 422
        postJson("/api/regional/allocations", allocateBody(ctx, "second",
                segment(ctx, "A", T_1030Z, T_1045Z), segment(ctx, "C", T_1130Z, T_1145Z)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REGIONAL_ALLOCATION_REJECTED"))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].regionId").value(ctx.region("A")))
                .andExpect(jsonPath("$.conflicts[0].reason").value("REGION_WINDOW_OVERLAP"))
                .andExpect(jsonPath("$.conflicts[0].conflictingRequestId").value(ctx.rq("first")))
                .andExpect(jsonPath("$.conflicts[0].conflictingAssetId").value(ctx.asset));

        // 不留部分区域占位：C 无任何时段，本测试总占位仍只有首次的 A/B 两段
        getRegionAllocations(ctx, "C")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(0));
        assertThat(allocationCount(ctx)).isEqualTo(2);
    }

    // ---------- 授权窗口：超出窗口/已撤销均 422；撤销只影响之后，历史固化 ----------

    @Test
    void grantWindowAndRevocationOnlyAffectsFutureWhileHistoryStaysFrozen() throws Exception {
        Ctx ctx = newContext();
        long grantV1 = ctx.createRegionGrant("A", T_10Z, T_11Z);

        // 时段超出授权窗口：422 且无占位
        postJson("/api/regional/allocations", allocateBody(ctx, "wide",
                segment(ctx, "A", T_1030Z, T_1130Z)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("GRANT_NOT_COVERING"));
        getRegionAllocations(ctx, "A")
                .andExpect(jsonPath("$.segments.length()").value(0));

        // 窗口内提交成功，固化 grantId=v1、version=1
        postJson("/api/regional/allocations", allocateBody(ctx, "ok",
                segment(ctx, "A", T_1030Z, T_1045Z)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].grantId").value(grantV1))
                .andExpect(jsonPath("$.segments[0].grantVersion").value(1));

        // 撤销授权（幂等：重放 200，换 requestId 再撤 409）
        postJson("/api/regional/grants/" + grantV1 + "/revoke", Map.of("requestId", ctx.rq("rv1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        postJson("/api/regional/grants/" + grantV1 + "/revoke", Map.of("requestId", ctx.rq("rv1")))
                .andExpect(status().isOk());
        postJson("/api/regional/grants/" + grantV1 + "/revoke", Map.of("requestId", ctx.rq("rv2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GRANT_ALREADY_REVOKED"));

        // 授权变化只影响之后：撤销后无法再用该窗口编排（新段因授权失效 422）
        postJson("/api/regional/allocations", allocateBody(ctx, "after",
                segment(ctx, "A", T_1045Z, T_1050Z)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("GRANT_NOT_COVERING"));

        // 历史已固化：占位仍在，grantId/grantVersion 不追改
        getRegionAllocations(ctx, "A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].grantId").value(grantV1))
                .andExpect(jsonPath("$.segments[0].grantVersion").value(1))
                .andExpect(jsonPath("$.segments[0].assetId").value(ctx.asset));

        // 之后为同区域同素材再授权：版本继续递增（v2），新窗口编排成功并固化新版本
        long grantV2 = ctx.createRegionGrant("A", T_13Z, T_14Z);
        assertThat(grantV2).isNotEqualTo(grantV1);
        postJson("/api/regional/allocations", allocateBody(ctx, "v2",
                segment(ctx, "A", T_13Z, T_1330Z)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].grantId").value(grantV2))
                .andExpect(jsonPath("$.segments[0].grantVersion").value(2));
        // 旧行仍为 v1，新行为 v2（按开始时刻排序）
        JsonNode segments = readJson(getRegionAllocations(ctx, "A").andReturn()).get("segments");
        assertThat(segments.get(0).get("grantVersion").asLong()).isEqualTo(1L);
        assertThat(segments.get(1).get("grantVersion").asLong()).isEqualTo(2L);
    }

    // ---------- 左闭右开：相邻端点可接续，端点之外相交即重叠 ----------

    @Test
    void halfOpenAdjacencyAllowedInteriorOverlapRejected() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_12Z);

        postJson("/api/regional/allocations", allocateBody(ctx, "a1",
                segment(ctx, "A", T_10Z, T_11Z)))
                .andExpect(status().isOk());
        // 相邻 11:00-12:00：端点接续合法
        postJson("/api/regional/allocations", allocateBody(ctx, "a2",
                segment(ctx, "A", T_11Z, T_12Z)))
                .andExpect(status().isOk());
        // 10:59-11:01 同时跨过两段：422
        postJson("/api/regional/allocations", allocateBody(ctx, "a3",
                segment(ctx, "A", T_1059Z, T_1101Z)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("REGION_WINDOW_OVERLAP"));

        // 同一单内同一区域出现两段：400
        postJson("/api/regional/allocations", allocateBody(ctx, "a4",
                segment(ctx, "A", T_13Z, T_1330Z), segment(ctx, "A", T_1330Z, T_14Z)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等：重放首次清单，换素材/时段/区域 409，失败不占键 ----------

    @Test
    void idempotencyReplayAndConflictsAndFailureDoesNotOccupyKey() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_12Z);
        ctx.createRegionGrant("B", T_11Z, T_12Z);
        ctx.createRegionGrant("C", T_11Z, T_12Z);

        Map<String, Object> body = allocateBody(ctx, "idem",
                segment(ctx, "A", T_10Z, T_11Z), segment(ctx, "B", T_11Z, T_12Z));
        postJson("/api/regional/allocations", body).andExpect(status().isOk());

        // 同 requestId 同参数重放：返回首次区域清单，不新增占位
        postJson("/api/regional/allocations", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].regionId").value(ctx.region("A")))
                .andExpect(jsonPath("$.segments[1].regionId").value(ctx.region("B")));
        assertThat(allocationCount(ctx)).isEqualTo(2);

        // 换素材：409（幂等指纹不一致在素材存在性校验之前判定）
        postJson("/api/regional/allocations", allocateBody(ctx.asset + "x", ctx.rq("idem"),
                segment(ctx, "A", T_10Z, T_11Z), segment(ctx, "B", T_11Z, T_12Z)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 换时段：409
        postJson("/api/regional/allocations", allocateBody(ctx.asset, ctx.rq("idem"),
                segment(ctx, "A", T_10Z, T_1045Z), segment(ctx, "B", T_11Z, T_12Z)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 换区域：409
        postJson("/api/regional/allocations", allocateBody(ctx.asset, ctx.rq("idem"),
                segment(ctx, "A", T_10Z, T_11Z), segment(ctx, "C", T_11Z, T_12Z)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败不占 requestId：先以重叠段 422，再用同 requestId 提交合法相邻段成功
        postJson("/api/regional/allocations", allocateBody(ctx, "retry",
                segment(ctx, "A", T_1030Z, T_1045Z)))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/regional/allocations", allocateBody(ctx, "retry",
                segment(ctx, "A", T_11Z, T_12Z)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].regionId").value(ctx.region("A")));
    }

    // ---------- 并发：同一区域窗口最多一方提交成功 ----------

    @Test
    void concurrentSameRegionWindowOnlyOneWins() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_12Z);
        String url = "/api/regional/allocations";

        List<Integer> statuses = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(allocateBody(ctx.asset,
                                ctx.rq("cc" + i), segment(ctx, "A", T_10Z, T_11Z)))))
                .andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // 最终数据：该窗口只有一条占位
        JsonNode segments = readJson(getRegionAllocations(ctx, "A").andReturn()).get("segments");
        assertThat(segments.size()).isEqualTo(1);
        assertThat(segments.get(0).get("regionId").asText()).isEqualTo(ctx.region("A"));
        assertThat(segments.get(0).get("requestId").asText()).startsWith(ctx.rq("cc"));
    }

    // ---------- 参数与引用校验 ----------

    @Test
    void validationAndReferenceBranches() throws Exception {
        Ctx ctx = newContext();
        ctx.createRegionGrant("A", T_10Z, T_11Z);

        // 400：空段清单
        postJson("/api/regional/allocations", allocateBody(ctx, "empty"))
                .andExpect(status().isBadRequest());
        // 400：缺 requestId
        Map<String, Object> noReq = new LinkedHashMap<>();
        noReq.put("assetId", ctx.asset);
        noReq.put("segments", List.of(segment(ctx, "A", T_10Z, T_11Z)));
        postJson("/api/regional/allocations", noReq).andExpect(status().isBadRequest());
        // 400：结束不大于开始
        postJson("/api/regional/allocations", allocateBody(ctx, "inv",
                segment(ctx, "A", T_11Z, T_10Z)))
                .andExpect(status().isBadRequest());
        // 404：素材不存在
        postJson("/api/regional/allocations", allocateBody(ctx.asset + "ghost", ctx.rq("na"),
                segment(ctx, "A", T_10Z, T_11Z)))
                .andExpect(status().isNotFound());
        // 404：区域不存在
        postJson("/api/regional/allocations", allocateBody(ctx.asset, ctx.rq("nr"),
                segmentRaw(ctx.prefix + "ghost", T_10Z, T_11Z)))
                .andExpect(status().isNotFound());
        // 404：给不存在区域建授权
        postJson("/api/regional/regions/" + ctx.prefix + "ghost/grants",
                        Map.of("assetId", ctx.asset, "validFrom", T_10Z, "validTo", T_11Z))
                .andExpect(status().isNotFound());
        // 400：授权区间终点不大于起点
        postJson("/api/regional/regions/" + ctx.region("A") + "/grants",
                        Map.of("assetId", ctx.asset, "validFrom", T_11Z, "validTo", T_10Z))
                .andExpect(status().isBadRequest());
        // 409：区域 ID 重复
        postJson("/api/regional/regions", Map.of("id", ctx.region("A")))
                .andExpect(status().isConflict());
    }

    // ---------- 测试辅助 ----------

    private final class Ctx {
        private final String prefix = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private final String asset = prefix + "S";
        private final Set<String> createdRegions = new HashSet<>();

        private String region(String shortId) {
            return prefix + shortId;
        }

        /** 幂等 requestId 也必须全局唯一：playout_request 表在全部测试间共享。 */
        private String rq(String id) {
            return prefix + "req-" + id;
        }

        private void baseSetup() throws Exception {
            postJson("/api/assets", Map.of("id", asset, "durationMs", 3600000))
                    .andExpect(status().isOk());
        }

        private void createRegion(String shortId) throws Exception {
            if (!createdRegions.add(region(shortId))) {
                return;
            }
            postJson("/api/regional/regions", Map.of("id", region(shortId)))
                    .andExpect(status().isOk());
        }

        private long createRegionGrant(String shortId, String from, String to) throws Exception {
            createRegion(shortId);
            MvcResult result = postJson("/api/regional/regions/" + region(shortId) + "/grants",
                            Map.of("assetId", asset, "validFrom", from, "validTo", to))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("id").asLong();
        }
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.baseSetup();
        return ctx;
    }

    private static Map<String, Object> segment(Ctx ctx, String shortId, String start, String end) {
        return segmentRaw(ctx.region(shortId), start, end);
    }

    private static Map<String, Object> segmentRaw(String regionId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("regionId", regionId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    private Map<String, Object> allocateBody(Ctx ctx, String requestKey, Object... segments) {
        return allocateBody(ctx.asset, ctx.rq(requestKey), segments);
    }

    private Map<String, Object> allocateBody(String assetId, String requestId, Object... segments) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object segment : segments) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) segment;
            list.add(typed);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("assetId", assetId);
        body.put("segments", list);
        return body;
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    private ResultActions getRegionAllocations(Ctx ctx, String shortId) throws Exception {
        return getJson("/api/regional/regions/" + ctx.region(shortId) + "/allocations");
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 编排员视图中属于本测试素材的占位（库可能含其他测试数据）。 */
    private List<JsonNode> myAllocations(Ctx ctx) throws Exception {
        JsonNode all = readJson(getJson("/api/regional/allocations").andReturn()).get("segments");
        List<JsonNode> mine = new ArrayList<>();
        for (JsonNode node : all) {
            if (node.get("assetId").asText().equals(ctx.asset)) {
                mine.add(node);
            }
        }
        return mine;
    }

    private int allocationCount(Ctx ctx) throws Exception {
        return myAllocations(ctx).size();
    }

    private List<String> regionIdsOfMine(Ctx ctx) throws Exception {
        return myAllocations(ctx).stream()
                .map(node -> node.get("regionId").asText()).toList();
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
                start.await(10, TimeUnit.SECONDS);
                return action.get(index);
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (Future<Integer> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}
