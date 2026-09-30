package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多来源水质混配 API 集成测试：成功受理、阈值边界、整单失败回滚、取消释放、
 * requestId 幂等重放/改参 409、两申请并发争用同一来源。全部使用 H2 内存库（MODE=MySQL）
 * 的真实事务、唯一约束与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlendApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlendService blendService;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Map<String, Object> body, String actor, int expectedStatus)
            throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode postOk(String url, Map<String, Object> body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> sourceBody(String sourceKey, String name, String index, String quota) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("sc"));
        body.put("sourceKey", sourceKey);
        body.put("name", name);
        body.put("qualityIndex", index);
        body.put("totalQuota", quota);
        return body;
    }

    private String registerSource(String shortName, String index, String quota) throws Exception {
        String sourceKey = key("src");
        postOk("/api/blend/sources", sourceBody(sourceKey, "来源-" + shortName, index, quota), "admin");
        return sourceKey;
    }

    private Map<String, Object> blendBody(String requestId, String userId, String purpose, String threshold,
                                          List<Map<String, Object>> sources) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("userId", userId);
        body.put("purpose", purpose);
        body.put("threshold", threshold);
        body.put("sources", sources);
        return body;
    }

    private JsonNode getSource(String sourceKey) throws Exception {
        return getOk("/api/blend/sources/" + sourceKey);
    }

    private JsonNode getApplication(String requestId) throws Exception {
        return getOk("/api/blend/applications/" + requestId);
    }

    // ------------------------------------------------------------------
    // 主流程（题设：甲10/指标2、乙10/指标4、阈值3 -> 加权3 可预占）
    // ------------------------------------------------------------------

    @Test
    void blendOfTwoSourcesIsAcceptedAtExactThresholdAndReservesAtomically() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");

        String requestId = key("req");
        Map<String, Object> body = blendBody(requestId, "user-1", "IRRIGATION", "3",
                List.of(Map.of("sourceKey", jia, "volume", "10", "qualityIndex", "2"),
                        Map.of("sourceKey", yi, "volume", "10", "qualityIndex", "4")));
        JsonNode result = postOk("/api/blend/applications", body, "alice");

        assertEquals(requestId, result.get("requestId").asText());
        assertEquals("RESERVED", result.get("status").asText());
        assertEquals("20", result.get("totalVolume").asText());
        // (10×2 + 10×4) / 20 = 3
        assertEquals("3", result.get("weightedIndex").asText());
        assertEquals("alice", result.get("requester").asText());
        assertEquals("IRRIGATION", result.get("purpose").asText());
        assertEquals("3", result.get("threshold").asText());
        assertNotNull(result.get("createdUtc"));
        assertTrue(result.get("cancelledUtc").isNull());

        JsonNode items = result.get("sources");
        assertEquals(2, items.size());
        assertEquals(jia, items.get(0).get("sourceKey").asText());
        assertEquals("10", items.get(0).get("volume").asText());
        assertEquals("2", items.get(0).get("qualityIndex").asText());
        assertEquals("3", items.get(0).get("weightedIndex").asText());
        assertEquals("RESERVED", items.get(0).get("reservedStatus").asText());
        assertEquals(yi, items.get(1).get("sourceKey").asText());
        assertEquals("4", items.get(1).get("qualityIndex").asText());
        assertEquals("RESERVED", items.get(1).get("reservedStatus").asText());

        // 两个来源各自预占 10，余量归零
        JsonNode jiaState = getSource(jia);
        assertEquals("10", jiaState.get("reservedVolume").asText());
        assertEquals("0", jiaState.get("remaining").asText());
        JsonNode yiState = getSource(yi);
        assertEquals("10", yiState.get("reservedVolume").asText());
        assertEquals("0", yiState.get("remaining").asText());

        // 查询接口展示每种来源、体积、指标、加权值与预占状态
        JsonNode queried = getApplication(requestId);
        assertEquals(result, queried);
    }

    @Test
    void weightedBelowThresholdIsAcceptedAndVolumeWeightingIsExact() throws Exception {
        String jia = registerSource("jia", "2", "20");
        String yi = registerSource("yi", "5", "20");
        // (6×2 + 4×5)/10 = 3.2，阈值 3.3 可受理
        JsonNode result = postOk("/api/blend/applications",
                blendBody(key("req"), "u", "PURPOSE-X", "3.3",
                        List.of(Map.of("sourceKey", jia, "volume", "6", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "4", "qualityIndex", "5"))),
                "alice");
        assertEquals("3.2", result.get("weightedIndex").asText());
        assertEquals("6", getSource(jia).get("reservedVolume").asText());
        assertEquals("4", getSource(yi).get("reservedVolume").asText());
    }

    @Test
    void recurringWeightedValueEqualsRoundedThresholdIsAccepted() throws Exception {
        // 1×1 + 2×2 = 5，总体积 3，5/3 = 1.666666...，四舍五入到 4 位为 1.6667；等于阈值可受理
        String a = registerSource("a", "1", "10");
        String b = registerSource("b", "2", "10");
        JsonNode result = postOk("/api/blend/applications",
                blendBody(key("req"), "u", "P", "1.6667",
                        List.of(Map.of("sourceKey", a, "volume", "1", "qualityIndex", "1"),
                                Map.of("sourceKey", b, "volume", "2", "qualityIndex", "2"))),
                "alice");
        assertEquals("1.6667", result.get("weightedIndex").asText());
        assertEquals("RESERVED", result.get("status").asText());
    }

    // ------------------------------------------------------------------
    // 失败分支：整单 422 回滚，不扣任何来源额度
    // ------------------------------------------------------------------

    @Test
    void weightedAboveThresholdReturns422AndReservesNothing() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String requestId = key("req");
        // 加权 3，阈值 2.9999 -> 超阈值
        JsonNode error = objectMapper.readTree(postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "2.9999",
                        List.of(Map.of("sourceKey", jia, "volume", "10", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "10", "qualityIndex", "4"))),
                "alice", 422).getResponse().getContentAsString());
        assertEquals("BLEND_THRESHOLD_EXCEEDED", error.get("code").asText());

        // 任何来源都未扣减，申请与明细不存在
        assertEquals("0", getSource(jia).get("reservedVolume").asText());
        assertEquals("0", getSource(yi).get("reservedVolume").asText());
        mvc.perform(get("/api/blend/applications/" + requestId)).andExpect(status().isNotFound());
    }

    @Test
    void missingIndicatorReturns422AndReservesNothing() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String requestId = key("req");
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(Map.of("sourceKey", jia, "volume", "10", "qualityIndex", "2"));
        Map<String, Object> missing = new HashMap<>();
        missing.put("sourceKey", yi);
        missing.put("volume", "10");
        sources.add(missing);
        JsonNode error = objectMapper.readTree(postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3", sources), "alice", 422)
                .getResponse().getContentAsString());
        assertEquals("BLEND_INDICATOR_MISSING", error.get("code").asText());
        assertEquals("0", getSource(jia).get("reservedVolume").asText());
        assertEquals("0", getSource(yi).get("reservedVolume").asText());
        mvc.perform(get("/api/blend/applications/" + requestId)).andExpect(status().isNotFound());
    }

    @Test
    void oneSourceInsufficientQuotaReturns422AndNeitherSourceIsDebited() throws Exception {
        // 题设：乙额度不足时甲也不得扣减
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String requestId = key("req");
        JsonNode error = objectMapper.readTree(postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3.5",
                        List.of(Map.of("sourceKey", jia, "volume", "6", "qualityIndex", "2"),
                                // 乙要 14，配额只有 10；加权 (12+56)/20=3.4 ≤ 3.5，纯粹卡在额度
                                Map.of("sourceKey", yi, "volume", "14", "qualityIndex", "4"))),
                "alice", 422).getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());
        assertEquals("0", getSource(jia).get("reservedVolume").asText());
        assertEquals("10", getSource(jia).get("remaining").asText());
        assertEquals("0", getSource(yi).get("reservedVolume").asText());
        assertEquals("10", getSource(yi).get("remaining").asText());
        mvc.perform(get("/api/blend/applications/" + requestId)).andExpect(status().isNotFound());
    }

    @Test
    void alreadyReservedQuotaCountsAgainstNewApplication() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        postOk("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "8", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "8", "qualityIndex", "4"))),
                "alice");
        // 两来源各剩 2：再申请甲2+乙3 时乙不足 -> 整单 422，甲的 2 也不扣
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "3", "qualityIndex", "4"))),
                "alice", 422);
        assertEquals("8", getSource(jia).get("reservedVolume").asText());
        assertEquals("8", getSource(yi).get("reservedVolume").asText());
        // 甲2+乙2 恰好放得下
        JsonNode second = postOk("/api/blend/applications",
                blendBody(key("req"), "u2", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4"))),
                "bob");
        assertEquals("RESERVED", second.get("status").asText());
        assertEquals("10", getSource(jia).get("reservedVolume").asText());
        assertEquals("0", getSource(jia).get("remaining").asText());
    }

    @Test
    void unknownSourceAndBadParamsReturn422Or400() throws Exception {
        String jia = registerSource("jia", "2", "10");
        // 来源不存在 -> 422
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2"),
                                Map.of("sourceKey", "missing-" + run, "volume", "1", "qualityIndex", "2"))),
                "alice", 422);
        // 空来源列表 -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3", List.of()), "alice", 400);
        // 体积为零/负数 -> 400；阈值非法 -> 400；缺 requestId -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "0", "qualityIndex", "2"))),
                "alice", 400);
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "-1",
                        List.of(Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2"))),
                "alice", 400);
        Map<String, Object> noRequest = blendBody(key("req"), "u", "P", "3",
                List.of(Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2")));
        noRequest.remove("requestId");
        postJson("/api/blend/applications", noRequest, "alice", 400);
        // 同单重复来源 -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2"),
                                Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2"))),
                "alice", 400);
        // 缺 X-Actor-Id -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "1", "qualityIndex", "2"))),
                null, 400);
        // 失败尝试均未占用额度
        assertEquals("0", getSource(jia).get("reservedVolume").asText());
    }

    // ------------------------------------------------------------------
    // 取消：释放原预占量，历史计算依据保留不重算
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesReservationsAndKeepsHistoricalBasis() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String requestId = key("req");
        JsonNode accepted = postOk("/api/blend/applications",
                blendBody(requestId, "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "6", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "4", "qualityIndex", "4"))),
                "alice");
        assertEquals("6", getSource(jia).get("reservedVolume").asText());
        assertEquals("4", getSource(yi).get("reservedVolume").asText());

        // 非申请人取消 -> 409
        postJson("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("xcc")), "bob", 409);
        // 申请人取消 -> 200
        JsonNode cancelled = objectMapper.readTree(postJson("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("xcc")), "alice", 200).getResponse().getContentAsString());
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertNotNull(cancelled.get("cancelledUtc"));
        // 原总体积、加权值保留不重算（6×2 + 4×4 = 28，/10 = 2.8）
        assertEquals(accepted.get("totalVolume"), cancelled.get("totalVolume"));
        assertEquals("2.8", cancelled.get("weightedIndex").asText());
        for (JsonNode item : cancelled.get("sources")) {
            assertEquals("RELEASED", item.get("reservedStatus").asText());
            // 体积、指标、加权值快照保留
            assertNotNull(item.get("volume").asText());
            assertNotNull(item.get("qualityIndex").asText());
            assertEquals("2.8", item.get("weightedIndex").asText());
        }
        // 各来源原预占量释放
        assertEquals("0", getSource(jia).get("reservedVolume").asText());
        assertEquals("10", getSource(jia).get("remaining").asText());
        assertEquals("0", getSource(yi).get("reservedVolume").asText());
        assertEquals("10", getSource(yi).get("remaining").asText());
        // 查询仍可看到取消记录及其计算依据
        JsonNode queried = getApplication(requestId);
        assertEquals("CANCELLED", queried.get("status").asText());
        assertEquals(2, queried.get("sources").size());
        // 重复取消 -> 409
        postJson("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("xcc")), "alice", 409);
        // 释放后配额可被新申请重新预占
        JsonNode second = postOk("/api/blend/applications",
                blendBody(key("req"), "u2", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "10", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "10", "qualityIndex", "4"))),
                "carol");
        assertEquals("RESERVED", second.get("status").asText());
        assertEquals("10", getSource(jia).get("reservedVolume").asText());
    }

    @Test
    void cancelUnknownApplicationReturns404() throws Exception {
        postJson("/api/blend/applications/no-such-" + run + "/cancel",
                Map.of("commandKey", key("xcc")), "alice", 404);
        mvc.perform(get("/api/blend/sources/no-such-" + run)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // requestId 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstReservationAndResult() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String requestId = key("req");
        Map<String, Object> body = blendBody(requestId, "u", "P", "3",
                List.of(Map.of("sourceKey", jia, "volume", "3", "qualityIndex", "2"),
                        Map.of("sourceKey", yi, "volume", "3", "qualityIndex", "4")));
        JsonNode first = postOk("/api/blend/applications", body, "alice");
        JsonNode replay = postOk("/api/blend/applications", body, "alice");
        assertEquals(first, replay);
        // 重放不重复预占
        assertEquals("3", getSource(jia).get("reservedVolume").asText());
        assertEquals("3", getSource(yi).get("reservedVolume").asText());
        assertEquals("RESERVED", getApplication(requestId).get("status").asText());
    }

    @Test
    void sameRequestIdWithDifferentSourcesOrVolumeReturns409() throws Exception {
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String bing = registerSource("bing", "3", "10");
        String requestId = key("req");
        Map<String, Object> original = blendBody(requestId, "u", "P", "3",
                List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                        Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4")));
        JsonNode first = postOk("/api/blend/applications", original, "alice");

        // 改体积 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "3", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4"))),
                "alice", 409);
        // 改指标 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2.5"),
                                Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4"))),
                "alice", 409);
        // 改来源（乙换丙）-> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                                Map.of("sourceKey", bing, "volume", "2", "qualityIndex", "3"))),
                "alice", 409);
        // 删一个来源 -> 409；改用水户/用途/阈值/操作人 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"))),
                "alice", 409);
        postJson("/api/blend/applications",
                blendBody(requestId, "other-user", "P", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4"))),
                "alice", 409);
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "OTHER", "3",
                        List.of(Map.of("sourceKey", jia, "volume", "2", "qualityIndex", "2"),
                                Map.of("sourceKey", yi, "volume", "2", "qualityIndex", "4"))),
                "alice", 409);
        postJson("/api/blend/applications", original, "bob", 409);
        // 所有冲突重放都没有改变首次预占
        assertEquals("2", getSource(jia).get("reservedVolume").asText());
        assertEquals("2", getSource(yi).get("reservedVolume").asText());
        assertEquals("0", getSource(bing).get("reservedVolume").asText());
        // 同键同参仍返回首次结果
        assertEquals(first, postOk("/api/blend/applications", original, "alice"));
    }

    // ------------------------------------------------------------------
    // 并发：两个申请争用同一来源余额
    // ------------------------------------------------------------------

    @Test
    void concurrentApplicationsOnOneSourceNeverExceedQuota() throws Exception {
        // 来源甲配额 10、指标 2；两申请各取 8 和 7，阈值放宽到 5 使均能通过水质裁决
        String jia = registerSource("jia", "2", "10");
        String req1 = key("req");
        String req2 = key("req");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return submitStatus(req1, jia, "8");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return submitStatus(req2, jia, "7");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 8 与 7 单独都放得下，合计 15 > 10：恰好一个受理、一个 422
        int ok = (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0);
        int quotaFailed = (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0);
        assertEquals(1, ok, "s1=" + s1 + " s2=" + s2);
        assertEquals(1, quotaFailed);
        // 已提交预占总额不得超过可用量，且等于胜方本单预占量（8 或 7，按提交顺序裁决）
        JsonNode state = getSource(jia);
        String winnerVolume = s1 == 200 ? "8" : "7";
        assertEquals(winnerVolume, state.get("reservedVolume").asText());
        assertEquals(String.valueOf(10 - Integer.parseInt(winnerVolume)), state.get("remaining").asText());
        // 胜方申请存在且 RESERVED，负方申请整单回滚不存在
        String winner = s1 == 200 ? req1 : req2;
        String loser = s1 == 200 ? req2 : req1;
        assertEquals("RESERVED", getApplication(winner).get("status").asText());
        mvc.perform(get("/api/blend/applications/" + loser)).andExpect(status().isNotFound());
    }

    @Test
    void concurrentMultiSourceApplicationsReserveAllOrNothing() throws Exception {
        // 甲、乙各 10；申请1 = 甲6+乙4（加权2.8），申请2 = 甲6+乙7（加权约3.08），阈值 3.1
        String jia = registerSource("jia", "2", "10");
        String yi = registerSource("yi", "4", "10");
        String req1 = key("req");
        String req2 = key("req");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return submitMultiStatus(req1,
                    List.of(Map.of("sourceKey", jia, "volume", "6", "qualityIndex", "2"),
                            Map.of("sourceKey", yi, "volume", "4", "qualityIndex", "4")), "3.1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return submitMultiStatus(req2,
                    List.of(Map.of("sourceKey", jia, "volume", "6", "qualityIndex", "2"),
                            Map.of("sourceKey", yi, "volume", "7", "qualityIndex", "4")), "3.1");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 甲 6+6=12>10、乙 4+7=11>10：恰好一单成功，另一单整单回滚
        int ok = (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0);
        assertEquals(1, ok, "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode jiaState = getSource(jia);
        JsonNode yiState = getSource(yi);
        assertTrue(Double.parseDouble(jiaState.get("reservedVolume").asText()) <= 10);
        assertTrue(Double.parseDouble(yiState.get("reservedVolume").asText()) <= 10);
        if (s1 == 200) {
            assertEquals("6", jiaState.get("reservedVolume").asText());
            assertEquals("4", yiState.get("reservedVolume").asText());
            mvc.perform(get("/api/blend/applications/" + req2)).andExpect(status().isNotFound());
        } else {
            assertEquals("6", jiaState.get("reservedVolume").asText());
            assertEquals("7", yiState.get("reservedVolume").asText());
            mvc.perform(get("/api/blend/applications/" + req1)).andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------
    // 来源注册与命令幂等
    // ------------------------------------------------------------------

    @Test
    void registerSourceIdempotentAndRejectsDuplicates() throws Exception {
        String sourceKey = key("src");
        Map<String, Object> body = sourceBody(sourceKey, "来源-唯一", "2.5", "8");
        JsonNode first = postOk("/api/blend/sources", body, "admin");
        assertEquals("2.5", first.get("qualityIndex").asText());
        assertEquals("8", first.get("totalQuota").asText());
        assertEquals("0", first.get("reservedVolume").asText());
        // 同 commandKey 同参重放首次结果
        assertEquals(first, postOk("/api/blend/sources", body, "admin"));
        // 换 commandKey 重复 sourceKey -> 409
        postJson("/api/blend/sources", sourceBody(sourceKey, "来源-唯一", "2.5", "8"), "admin", 409);
        // 同 commandKey 改参 -> 409
        Map<String, Object> changed = sourceBody(sourceKey, "来源-唯一", "3.5", "8");
        changed.put("commandKey", body.get("commandKey"));
        postJson("/api/blend/sources", changed, "admin", 409);
        // 非法注册参数 -> 400
        postJson("/api/blend/sources", sourceBody(key("src"), "", "2", "5"), "admin", 400);
        postJson("/api/blend/sources", sourceBody(key("src"), "n", "-1", "5"), "admin", 400);
        postJson("/api/blend/sources", sourceBody(key("src"), "n", "2", "0"), "admin", 400);
        postJson("/api/blend/sources", sourceBody(key("src"), "n", "2", "5"), null, 400);
    }

    // ------------------------------------------------------------------
    // 并发辅助：直接调用服务，返回 HTTP 状态码
    // ------------------------------------------------------------------

    private int submitStatus(String requestId, String sourceKey, String volume) {
        try {
            blendService.submitBlend(requestId, "u", "P", "5",
                    List.of(new com.example.starter.water.dto.Dtos.BlendSourceInput(sourceKey, volume, "2")),
                    "alice");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private int submitMultiStatus(String requestId, List<Map<String, Object>> rawSources, String threshold) {
        try {
            List<com.example.starter.water.dto.Dtos.BlendSourceInput> sources = rawSources.stream()
                    .map(m -> new com.example.starter.water.dto.Dtos.BlendSourceInput(
                            (String) m.get("sourceKey"), (String) m.get("volume"), (String) m.get("qualityIndex")))
                    .toList();
            blendService.submitBlend(requestId, "u", "P", threshold, sources, "alice");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }
}
