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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 区域切换 API 端到端测试（真实 H2，MODE=MySQL 内存库）：
 * 覆盖题干主流程（A 10-11、B 11-12 同时提交，A 再申请 10:30 整单失败）、
 * 授权失效/时段重叠的整单 422 与部分区域不留占位、requestId 幂等与 409、
 * 同区域窗口并发最多一方成功、授权撤销只影响之后编排与历史固化、区域只读。
 * 唯一约束、FOR UPDATE 行锁与事务回滚均由真实数据库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegionPlayoutApiTest {

    private static final String T_10 = "2026-09-22T10:00:00.000+00:00";
    private static final String T_1030 = "2026-09-22T10:30:00.000+00:00";
    private static final String T_11 = "2026-09-22T11:00:00.000+00:00";
    private static final String T_1130 = "2026-09-22T11:30:00.000+00:00";
    private static final String T_12 = "2026-09-22T12:00:00.000+00:00";
    private static final String T_1230 = "2026-09-22T12:30:00.000+00:00";
    private static final String T_13 = "2026-09-22T13:00:00.000+00:00";
    private static final String T_14 = "2026-09-22T14:00:00.000+00:00";
    private static final String T_15 = "2026-09-22T15:00:00.000+00:00";
    private static final String DAY_FROM = "2026-09-22T00:00:00.000+00:00";
    private static final String DAY_TO = "2026-09-23T00:00:00.000+00:00";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 题干主流程 ----------

    @Test
    void twoRegionsSubmittedTogetherThenThirdApplicationFailsWithoutPartialOccupancy() throws Exception {
        Ctx ctx = newContext();
        createGrant(ctx.regionA, ctx.asset, T_10, T_11);
        createGrant(ctx.regionB, ctx.asset, T_11, T_12);

        // 素材 S 授权区域 A 10:00-11:00、B 11:00-12:00，两段同一编排同时提交成功
        Map<String, Object> body = scheduleBody("req-" + ctx.prefix + "main",
                segment(ctx.regionA, ctx.asset, T_10, T_11),
                segment(ctx.regionB, ctx.asset, T_11, T_12));
        postJson("/api/region-playout/schedules", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-" + ctx.prefix + "main"))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].regionId").value(ctx.regionA))
                .andExpect(jsonPath("$.allocations[0].assetId").value(ctx.asset))
                .andExpect(jsonPath("$.allocations[0].start").value(T_10))
                .andExpect(jsonPath("$.allocations[0].grantValidFrom").value(T_10))
                .andExpect(jsonPath("$.allocations[0].grantValidTo").value(T_11))
                .andExpect(jsonPath("$.allocations[1].regionId").value(ctx.regionB));

        // 编排员视角：两区域各一条占位
        assertThat(allocationsOf(ctx.regionA)).hasSize(1);
        assertThat(allocationsOf(ctx.regionB)).hasSize(1);

        // A 再申请 10:30 播出：整单 422，冲突原因定位到区域 A 的窗口重叠
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "again",
                        segment(ctx.regionA, ctx.asset, T_1030, T_11)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REGION_SCHEDULE_INVALID"))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].regionId").value(ctx.regionA))
                .andExpect(jsonPath("$.conflicts[0].reason").value("REGION_WINDOW_CONFLICT"));

        // 不留下任何新占位：A、B 仍各只有首次的一条
        assertThat(allocationsOf(ctx.regionA)).hasSize(1);
        assertThat(allocationsOf(ctx.regionB)).hasSize(1);
        assertThat(allAllocations()).filteredOn(a -> a.get("requestId").asText()
                .equals("req-" + ctx.prefix + "again")).isEmpty();
    }

    // ---------- 相邻端点可接续 ----------

    @Test
    void adjacentWindowsCanContinueInSameRegion() throws Exception {
        Ctx ctx = newContext();
        // B 授权 11:00-13:00，先占 11:00-12:00
        createGrant(ctx.regionB, ctx.asset, T_11, T_13);
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "first",
                        segment(ctx.regionB, ctx.asset, T_11, T_12)))
                .andExpect(status().isOk());
        // 相邻端点 12:00 接续，不算重叠
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "next",
                        segment(ctx.regionB, ctx.asset, T_12, T_13)))
                .andExpect(status().isOk());
        assertThat(allocationsOf(ctx.regionB)).hasSize(2);

        // 端点两侧边界：11:00 起点含、12:00 前一区间终点不含 → 新区间从 11:30 开始仍冲突
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "touch",
                        segment(ctx.regionB, ctx.asset, T_1130, T_1230)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("REGION_WINDOW_CONFLICT"));
        assertThat(allocationsOf(ctx.regionB)).hasSize(2);
    }

    // ---------- 任一区域失败，整单回滚不留部分占位 ----------

    @Test
    void oneRegionConflictRollsBackAllRegionsInOrder() throws Exception {
        Ctx ctx = newContext();
        createRegion(ctx.prefix + "C");
        createGrant(ctx.regionA, ctx.asset, T_10, T_11);
        createGrant(ctx.regionB, ctx.asset, T_11, T_13);
        String regionC = ctx.prefix + "C";
        createGrant(regionC, ctx.asset, T_10, T_11);

        // 首单：A 10-11、B 11-12
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "ok",
                        segment(ctx.regionA, ctx.asset, T_10, T_11),
                        segment(ctx.regionB, ctx.asset, T_11, T_12)))
                .andExpect(status().isOk());

        // 次单：A 10:30 冲突 + B 12-13 合法相邻 + C 10-11 合法空闲 → 整单 422，三处中一处冲突
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "mix",
                        segment(ctx.regionA, ctx.asset, T_1030, T_11),
                        segment(ctx.regionB, ctx.asset, T_12, T_13),
                        segment(regionC, ctx.asset, T_10, T_11)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REGION_SCHEDULE_INVALID"));

        // 回滚后：B 不新增 12-13，C 完全没有占位
        assertThat(allocationsOf(ctx.regionB)).hasSize(1);
        assertThat(allocationsOf(regionC)).isEmpty();
    }

    // ---------- 授权失效与单内重叠的 422 分支 ----------

    @Test
    void grantNotCoveringRevokedAndInOrderOverlapReturn422() throws Exception {
        Ctx ctx = newContext();
        createRegion(ctx.prefix + "C");
        String regionC = ctx.prefix + "C";

        // 授权窗口只到 10:30，申请 10-11：422 NO_COVERING_GRANT
        createGrant(regionC, ctx.asset, T_10, T_1030);
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "nc",
                        segment(regionC, ctx.asset, T_10, T_11)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("NO_COVERING_GRANT"))
                .andExpect(jsonPath("$.conflicts[0].regionId").value(regionC));
        assertThat(allocationsOf(regionC)).isEmpty();

        // 授权撤销后提交：422 GRANT_INVALID
        long grantA = createGrant(ctx.regionA, ctx.asset, DAY_FROM, DAY_TO);
        postJson("/api/region-playout/grants/" + grantA + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "rev",
                        segment(ctx.regionA, ctx.asset, T_10, T_11)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("GRANT_INVALID"));
        assertThat(allocationsOf(ctx.regionA)).isEmpty();

        // 单内同区域时段重叠（即使授权足够宽）：422 SEGMENT_OVERLAP
        long grantB = createGrant(ctx.regionB, ctx.asset, DAY_FROM, DAY_TO);
        assertThat(grantB).isPositive();
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "self",
                        segment(ctx.regionB, ctx.asset, T_10, T_11),
                        segment(ctx.regionB, ctx.asset, T_1030, T_1130)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("SEGMENT_OVERLAP"))
                .andExpect(jsonPath("$.conflicts[0].regionId").value(ctx.regionB));
        assertThat(allocationsOf(ctx.regionB)).isEmpty();
    }

    // ---------- 400 / 404 分支 ----------

    @Test
    void badRequestAndNotFoundBranches() throws Exception {
        Ctx ctx = newContext();
        createGrant(ctx.regionA, ctx.asset, DAY_FROM, DAY_TO);

        // 400：结束不大于开始
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "bad-time",
                        segment(ctx.regionA, ctx.asset, T_11, T_10)))
                .andExpect(status().isBadRequest());
        // 400：空时段清单
        mvc.perform(post("/api/region-playout/schedules").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requestId", "req-" + ctx.prefix + "empty", "segments", List.of()))))
                .andExpect(status().isBadRequest());
        // 400：缺 requestId
        mvc.perform(post("/api/region-playout/schedules").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("segments", List.of(segment(ctx.regionA, ctx.asset, T_10, T_11))))))
                .andExpect(status().isBadRequest());

        // 404：区域不存在
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "no-region",
                        segment(ctx.prefix + "ghost", ctx.asset, T_10, T_11)))
                .andExpect(status().isNotFound());
        // 404：素材不存在
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "no-asset",
                        segment(ctx.regionA, ctx.prefix + "ghost", T_10, T_11)))
                .andExpect(status().isNotFound());
        // 404：区域只读接口查询不存在区域
        getJson("/api/region-playout/regions/" + ctx.prefix + "ghost/allocations")
                .andExpect(status().isNotFound());
        // 404：区域授权不存在
        postJson("/api/region-playout/grants/999999/revoke", Map.of("requestId", "req-ghost-rv"))
                .andExpect(status().isNotFound());

        // 409：区域 ID 重复
        postJson("/api/region-playout/regions", Map.of("id", ctx.regionA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_ID"));
        // 400：授权区间终点不大于起点
        postJson("/api/region-playout/grants", Map.of(
                        "regionId", ctx.regionA, "assetId", ctx.asset,
                        "validFrom", T_11, "validTo", T_10))
                .andExpect(status().isBadRequest());
    }

    // ---------- 幂等：重放首次清单、换素材/时段 409、失败不占键 ----------

    @Test
    void idempotentReplayChangedParamsConflictAndFailureReusable() throws Exception {
        Ctx ctx = newContext();
        createGrant(ctx.regionA, ctx.asset, DAY_FROM, DAY_TO);
        createGrant(ctx.regionB, ctx.asset, DAY_FROM, DAY_TO);
        String anotherAsset = ctx.prefix + "other";
        postJson("/api/assets", Map.of("id", anotherAsset, "durationMs", 1000))
                .andExpect(status().isOk());
        createGrant(ctx.regionA, anotherAsset, DAY_FROM, DAY_TO);

        Map<String, Object> body = scheduleBody("req-" + ctx.prefix + "idem",
                segment(ctx.regionA, ctx.asset, T_10, T_11),
                segment(ctx.regionB, ctx.asset, T_11, T_12));
        // 首次成功，记录首次分配 ID
        MvcResult first = postJson("/api/region-playout/schedules", body)
                .andExpect(status().isOk()).andReturn();
        JsonNode firstJson = readJson(first);
        assertThat(firstJson.get("allocations").size()).isEqualTo(2);
        long firstAllocId = firstJson.get("allocations").get(0).get("id").asLong();

        // 同 requestId 同参数重放：返回首次区域清单与相同分配 ID，不新增占位
        MvcResult replay = postJson("/api/region-playout/schedules", body)
                .andExpect(status().isOk()).andReturn();
        JsonNode replayJson = readJson(replay);
        assertThat(replayJson.get("allocations").get(0).get("id").asLong()).isEqualTo(firstAllocId);
        assertThat(allocationsOf(ctx.regionA)).hasSize(1);
        assertThat(allocationsOf(ctx.regionB)).hasSize(1);

        // 同 requestId 换素材：409
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "idem",
                        segment(ctx.regionA, anotherAsset, T_10, T_11),
                        segment(ctx.regionB, ctx.asset, T_11, T_12)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 换时段：409
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "idem",
                        segment(ctx.regionA, ctx.asset, T_10, T_1030),
                        segment(ctx.regionB, ctx.asset, T_11, T_12)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ID_CONFLICT"));

        // 失败请求不占用 requestId：先因与已有占位重叠 422，再用同 requestId 申请空闲相邻窗口成功
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "retry",
                        segment(ctx.regionA, ctx.asset, T_1030, T_11)))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "retry",
                        segment(ctx.regionB, ctx.asset, T_12, T_13)))
                .andExpect(status().isOk());
    }

    // ---------- 授权撤销幂等 ----------

    @Test
    void regionGrantRevokeIsIdempotentAndRejectsDoubleRevoke() throws Exception {
        Ctx ctx = newContext();
        long grantId = createGrant(ctx.regionA, ctx.asset, DAY_FROM, DAY_TO);
        postJson("/api/region-playout/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        // 同 requestId 重放：返回原结果
        postJson("/api/region-playout/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        // 新 requestId 重复撤销：409
        postJson("/api/region-playout/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REGION_GRANT_ALREADY_REVOKED"));
    }

    // ---------- 授权变化只影响之后编排，历史占位固化素材与授权版本 ----------

    @Test
    void revokeAfterSubmissionFreezesHistoryAndOnlyBlocksLaterSchedules() throws Exception {
        Ctx ctx = newContext();
        long grantId = createGrant(ctx.regionA, ctx.asset, T_10, T_12);

        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "freeze",
                        segment(ctx.regionA, ctx.asset, T_10, T_11)))
                .andExpect(status().isOk());

        // 撤销授权
        postJson("/api/region-playout/grants/" + grantId + "/revoke",
                        Map.of("requestId", "req-" + ctx.prefix + "rv"))
                .andExpect(status().isOk());

        // 历史已播出记录（占位）不追改：仍在，且固化素材、授权 ID 与授权窗口快照
        JsonNode frozen = allocationsOf(ctx.regionA).get(0);
        assertThat(frozen.get("assetId").asText()).isEqualTo(ctx.asset);
        assertThat(frozen.get("grantId").asLong()).isEqualTo(grantId);
        assertThat(frozen.get("grantValidFrom").asText()).isEqualTo(T_10);
        assertThat(frozen.get("grantValidTo").asText()).isEqualTo(T_12);

        // 授权变化只影响之后的编排：同一窗口再申请（与历史占位无关的判定路径之外，授权已失效）
        // 先验证另一空闲窗口 11-12 因授权撤销被拒（而非窗口冲突）
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "after",
                        segment(ctx.regionA, ctx.asset, T_11, T_12)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflicts[0].reason").value("GRANT_INVALID"));
        assertThat(allocationsOf(ctx.regionA)).hasSize(1);
    }

    // ---------- 区域播控方只读本区域 ----------

    @Test
    void regionControllerSeesOnlyOwnRegionWhileSchedulerSeesAll() throws Exception {
        Ctx ctx = newContext();
        createGrant(ctx.regionA, ctx.asset, T_10, T_11);
        createGrant(ctx.regionB, ctx.asset, T_11, T_12);
        postJson("/api/region-playout/schedules", scheduleBody("req-" + ctx.prefix + "scope",
                        segment(ctx.regionA, ctx.asset, T_10, T_11),
                        segment(ctx.regionB, ctx.asset, T_11, T_12)))
                .andExpect(status().isOk());

        List<JsonNode> all = allAllocations();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all).extracting(a -> a.get("regionId").asText())
                .contains(ctx.regionA, ctx.regionB);

        List<JsonNode> onlyA = allocationsOf(ctx.regionA);
        assertThat(onlyA).isNotEmpty();
        assertThat(onlyA).allSatisfy(a ->
                assertThat(a.get("regionId").asText()).isEqualTo(ctx.regionA));
    }

    // ---------- 并发：同一区域窗口最多一方成功 ----------

    @Test
    void concurrentSameRegionWindowOnlyOneWinsAndAdjacentBothWin() throws Exception {
        Ctx ctx = newContext();
        createGrant(ctx.regionA, ctx.asset, DAY_FROM, DAY_TO);
        String url = "/api/region-playout/schedules";

        // 两个不同 requestId 并发申请同区域同一窗口：恰好一方成功
        List<Integer> overlap = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                "req-" + ctx.prefix + "race" + i,
                                segment(ctx.regionA, ctx.asset, T_10, T_11)))))
                .andReturn().getResponse().getStatus());
        assertThat(overlap).containsExactlyInAnyOrder(200, 422);

        // 最终业务数据：该窗口只有一条占位
        List<JsonNode> afterOverlap = allocationsOf(ctx.regionA);
        assertThat(afterOverlap).hasSize(1);
        assertThat(afterOverlap.get(0).get("start").asText()).isEqualTo(T_10);

        // 并发申请相邻窗口：双方均成功
        List<Integer> adjacent = runConcurrently(2, i -> {
            String start = i == 0 ? T_11 : T_1130;
            String end = i == 0 ? T_1130 : T_12;
            return mvc.perform(post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleBody(
                                    "req-" + ctx.prefix + "adj" + i,
                                    segment(ctx.regionA, ctx.asset, start, end)))))
                    .andReturn().getResponse().getStatus();
        });
        assertThat(adjacent).containsExactly(200, 200);
        assertThat(allocationsOf(ctx.regionA)).hasSize(3);

        // 并发混合：两单都竞争 A 的全新窗口 14-15，并同时申请空闲的 B 10-11；
        // 一方整单成功（A、B 各落一条），另一方整单 422 回滚，输方不得在 B 留下占位
        createGrant(ctx.regionB, ctx.asset, DAY_FROM, DAY_TO);
        List<Integer> mixed = runConcurrently(2, i -> mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                "req-" + ctx.prefix + "mix" + i,
                                segment(ctx.regionA, ctx.asset, T_14, T_15),
                                segment(ctx.regionB, ctx.asset, T_10, T_11)))))
                .andReturn().getResponse().getStatus());
        assertThat(mixed).containsExactlyInAnyOrder(200, 422);
        assertThat(allocationsOf(ctx.regionB)).hasSize(1);
        assertThat(allocationsOf(ctx.regionA)).hasSize(4);
    }

    // ---------- 测试辅助 ----------

    /** 预置上下文：素材 S、区域 A 与 B；区域授权按用例单独创建。 */
    private final class Ctx {
        private final String prefix = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "-";
        private String regionA;
        private String regionB;
        private String asset;
    }

    private Ctx newContext() throws Exception {
        Ctx ctx = new Ctx();
        ctx.regionA = ctx.prefix + "A";
        ctx.regionB = ctx.prefix + "B";
        ctx.asset = ctx.prefix + "S";
        postJson("/api/assets", Map.of("id", ctx.asset, "durationMs", 3600000))
                .andExpect(status().isOk());
        createRegion(ctx.regionA);
        createRegion(ctx.regionB);
        return ctx;
    }

    private void createRegion(String id) throws Exception {
        postJson("/api/region-playout/regions", Map.of("id", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    private long createGrant(String regionId, String assetId, String from, String to) throws Exception {
        MvcResult result = postJson("/api/region-playout/grants", Map.of(
                        "regionId", regionId, "assetId", assetId,
                        "validFrom", from, "validTo", to))
                .andExpect(status().isOk()).andReturn();
        return readJson(result).get("id").asLong();
    }

    private static Map<String, Object> segment(String regionId, String assetId, String start, String end) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("regionId", regionId);
        segment.put("assetId", assetId);
        segment.put("start", start);
        segment.put("end", end);
        return segment;
    }

    @SafeVarargs
    private static Map<String, Object> scheduleBody(String requestId, Map<String, Object>... segments) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            list.add(segment);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
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

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<JsonNode> allocationsOf(String regionId) throws Exception {
        MvcResult result = getJson("/api/region-playout/regions/" + regionId + "/allocations")
                .andExpect(status().isOk()).andReturn();
        return toList(readJson(result));
    }

    private List<JsonNode> allAllocations() throws Exception {
        MvcResult result = getJson("/api/region-playout/allocations")
                .andExpect(status().isOk()).andReturn();
        return toList(readJson(result));
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
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
