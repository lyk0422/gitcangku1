package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.water.dto.Dtos.BlendSourceInput;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多来源水质混配 API 集成测试：题干主流程、失败整单回滚、取消释放与历史保留、
 * requestId 幂等边界，以及两个申请争用同一来源余额的真实并发裁决。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
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

    private Map<String, Object> sourceBody(String commandKey, String sourceKey, String name,
                                           String quota, String qualityIndex) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("sourceKey", sourceKey);
        body.put("name", name);
        body.put("quotaVolume", quota);
        if (qualityIndex != null) {
            body.put("qualityIndex", qualityIndex);
        }
        return body;
    }

    private String createSource(String name, String quota, String qualityIndex) throws Exception {
        String sourceKey = key("src");
        postOk("/api/blend/sources", sourceBody(key("sc"), sourceKey, name, quota, qualityIndex), null);
        return sourceKey;
    }

    private Map<String, Object> item(String sourceKey, String volume) {
        Map<String, Object> item = new HashMap<>();
        item.put("sourceKey", sourceKey);
        item.put("volume", volume);
        return item;
    }

    private Map<String, Object> blendBody(String requestId, String userId, String purpose,
                                          String threshold, List<Map<String, Object>> sources) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("userId", userId);
        body.put("purpose", purpose);
        body.put("qualityThreshold", threshold);
        body.put("sources", sources);
        return body;
    }

    private JsonNode sourceView(String sourceKey) throws Exception {
        return getOk("/api/blend/sources/" + sourceKey);
    }

    private JsonNode applicationView(String requestId) throws Exception {
        return getOk("/api/blend/applications/" + requestId);
    }

    /** 直接调用服务层提交两来源申请，返回 HTTP 风格状态码，用于并发协调真实业务操作。 */
    private int submitTwo(String requestId, String jia, String yi, String volJia, String volYi,
                          String threshold, String actor) {
        try {
            blendService.submitBlend(requestId, "user-1", "irrigation", threshold,
                    List.of(new BlendSourceInput(jia, volJia), new BlendSourceInput(yi, volYi)), actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    /** 直接调用服务层提交单来源申请。 */
    private int submitOne(String requestId, String sourceKey, String volume, String threshold) {
        try {
            blendService.submitBlend(requestId, "user-1", "irrigation", threshold,
                    List.of(new BlendSourceInput(sourceKey, volume)), "alice");
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 题干主流程
    // ------------------------------------------------------------------

    @Test
    void tenAtIndex2AndTenAtIndex4Threshold3IsAccepted() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");

        String requestId = key("req");
        JsonNode result = postOk("/api/blend/applications",
                blendBody(requestId, "user-1", "灌溉", "3", List.of(item(jia, "10"), item(yi, "10"))),
                "alice");

        assertEquals(requestId, result.get("requestId").asText());
        assertEquals("RESERVED", result.get("status").asText());
        assertEquals("20", result.get("totalVolume").asText());
        assertEquals("3", result.get("weightedIndex").asText());
        assertEquals("3", result.get("qualityThreshold").asText());
        assertEquals("alice", result.get("requester").asText());
        assertEquals(2, result.get("sources").size());
        // 每来源：体积、指标、加权贡献值（体积×指标）、预占状态
        JsonNode first = result.get("sources").get(0);
        assertEquals(jia, first.get("sourceKey").asText());
        assertEquals("10", first.get("volume").asText());
        assertEquals("2", first.get("qualityIndex").asText());
        assertEquals("20", first.get("weightedValue").asText());
        assertTrue(first.get("reserved").asBoolean());
        JsonNode second = result.get("sources").get(1);
        assertEquals(yi, second.get("sourceKey").asText());
        assertEquals("4", second.get("qualityIndex").asText());
        assertEquals("40", second.get("weightedValue").asText());
        assertTrue(second.get("reserved").asBoolean());

        // 两来源各预占 10，可用归零
        JsonNode jiaView = sourceView(jia);
        assertEquals("10", jiaView.get("quotaVolume").asText());
        assertEquals("2", jiaView.get("qualityIndex").asText());
        assertEquals("10", jiaView.get("reservedVolume").asText());
        assertEquals("0", jiaView.get("availableVolume").asText());
        assertEquals("10", sourceView(yi).get("reservedVolume").asText());

        // 查询接口返回相同快照
        JsonNode queried = applicationView(requestId);
        assertEquals(result, queried);
    }

    @Test
    void weightedValueExactlyAtThresholdIsAcceptedButJustAboveIsRejected() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");

        // 等于阈值 3 -> 受理
        String eqRequest = key("req");
        postOk("/api/blend/applications",
                blendBody(eqRequest, "u", "p", "3", List.of(item(jia, "5"), item(yi, "5"))), "alice");
        assertEquals("3", applicationView(eqRequest).get("weightedIndex").asText());

        // 阈值低 0.000001，舍入后加权值 3.000000 超阈值 -> 422，且不扣任何来源
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "2.999999",
                        List.of(item(jia, "1"), item(yi, "1"))), "alice", 422);
        // 只有第一次申请的预占（各 5），失败申请未扣减
        assertEquals("5", sourceView(jia).get("reservedVolume").asText());
        assertEquals("5", sourceView(yi).get("reservedVolume").asText());
    }

    // ------------------------------------------------------------------
    // 失败整单回滚：任一来源不足/指标缺失/来源不存在，都不得扣减其他来源
    // ------------------------------------------------------------------

    @Test
    void insufficientQuotaOfOneSourceDeductsNothing() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");

        // 乙先被另一申请预占 8
        postOk("/api/blend/applications",
                blendBody(key("req"), "u", "p", "4", List.of(item(jia, "2"), item(yi, "8"))), "alice");
        // 再申请甲10、乙3：乙仅剩 2，整单 422
        JsonNode error = objectMapper.readTree(postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "4", List.of(item(jia, "10"), item(yi, "3"))),
                "alice", 422).getResponse().getContentAsString());
        assertEquals("QUOTA_EXCEEDED", error.get("code").asText());

        // 甲保持首笔申请的预占 2（失败申请的 10 未被扣减），乙仍只预占 8
        assertEquals("2", sourceView(jia).get("reservedVolume").asText());
        assertEquals("8", sourceView(jia).get("availableVolume").asText());
        assertEquals("8", sourceView(yi).get("reservedVolume").asText());
        assertEquals("2", sourceView(yi).get("availableVolume").asText());
    }

    @Test
    void missingQualityIndexRejectsWholeApplicationAndDeductsNothing() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String bing = createSource("来源丙", "10", null);
        assertTrue(sourceView(bing).get("qualityIndex").isNull());

        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "5", List.of(item(jia, "5"), item(bing, "5"))),
                "alice", 422);
        // 指标缺失来源与正常来源都不扣减
        assertEquals("0", sourceView(jia).get("reservedVolume").asText());
        assertEquals("0", sourceView(bing).get("reservedVolume").asText());
    }

    @Test
    void unknownSourceReturns404AndDeductsNothing() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "5",
                        List.of(item(jia, "5"), item("missing-" + run, "5"))), "alice", 404);
        assertEquals("0", sourceView(jia).get("reservedVolume").asText());
        // 来源查询不存在 -> 404；申请查询不存在 -> 404
        mvc.perform(get("/api/blend/sources/missing-" + run)).andExpect(status().isNotFound());
        mvc.perform(get("/api/blend/applications/missing-" + run)).andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");

        // 来源列表为空 -> 总体积为零，400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of()), "alice", 400);
        // 体积 0 / 负 / 非数字 / 超 3 位小数 -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "0"), item(yi, "1"))),
                "alice", 400);
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "-1"), item(yi, "1"))),
                "alice", 400);
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "x"), item(yi, "1"))),
                "alice", 400);
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "1.0001"), item(yi, "1"))),
                "alice", 400);
        // 同一申请内来源重复 -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "1"), item(jia, "1"))),
                "alice", 400);
        // 阈值非法/负数 -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "abc", List.of(item(jia, "1"), item(yi, "1"))),
                "alice", 400);
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "-1", List.of(item(jia, "1"), item(yi, "1"))),
                "alice", 400);
        // 缺少 X-Actor-Id -> 400
        postJson("/api/blend/applications",
                blendBody(key("req"), "u", "p", "3", List.of(item(jia, "1"), item(yi, "1"))),
                null, 400);

        // 全部失败后无任何预占
        assertEquals("0", sourceView(jia).get("reservedVolume").asText());
        assertEquals("0", sourceView(yi).get("reservedVolume").asText());
    }

    // ------------------------------------------------------------------
    // 取消：释放原预占量、保留计算依据、权限与重复取消
    // ------------------------------------------------------------------

    @Test
    void cancelReleasesReservationButKeepsCalculationSnapshot() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");
        String requestId = key("req");
        JsonNode reserved = postOk("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(yi, "10"))), "alice");

        // 非申请人取消 -> 409
        postJson("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("cc")), "bob", 409);

        // 申请人取消 -> 200
        JsonNode cancelled = postOk("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("3", cancelled.get("weightedIndex").asText());
        assertEquals("20", cancelled.get("totalVolume").asText());
        assertFalse(cancelled.get("sources").get(0).get("reserved").asBoolean());
        assertFalse(cancelled.get("sources").get(1).get("reserved").asBoolean());
        // 原计算依据保留：体积、指标、加权贡献值不重算、不抹除
        assertEquals("20", cancelled.get("sources").get(0).get("weightedValue").asText());
        assertEquals("40", cancelled.get("sources").get(1).get("weightedValue").asText());
        assertEquals("2", cancelled.get("sources").get(0).get("qualityIndex").asText());

        // 预占释放：两来源可用恢复
        assertEquals("0", sourceView(jia).get("reservedVolume").asText());
        assertEquals("10", sourceView(jia).get("availableVolume").asText());
        assertEquals("0", sourceView(yi).get("reservedVolume").asText());
        assertEquals("10", sourceView(yi).get("availableVolume").asText());

        // 重复取消 -> 409
        postJson("/api/blend/applications/" + requestId + "/cancel",
                Map.of("commandKey", key("cc")), "alice", 409);

        // 释放后新申请（新 requestId）可再次占满
        String again = key("req");
        postOk("/api/blend/applications",
                blendBody(again, "u", "p", "3", List.of(item(jia, "10"), item(yi, "10"))), "alice");
        assertEquals("10", sourceView(jia).get("reservedVolume").asText());
        // 历史申请查询仍是取消时的快照，不随后续预占重算
        JsonNode history = applicationView(requestId);
        assertEquals("CANCELLED", history.get("status").asText());
        assertEquals("3", history.get("weightedIndex").asText());
        assertFalse(history.get("sources").get(0).get("reserved").asBoolean());
        assertEquals(reserved.get("weightedIndex"), history.get("weightedIndex"));
    }

    // ------------------------------------------------------------------
    // requestId 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstQuotaAndResult() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");
        String requestId = key("req");
        Map<String, Object> body = blendBody(requestId, "u", "p", "3",
                List.of(item(jia, "10"), item(yi, "10")));

        JsonNode first = postOk("/api/blend/applications", body, "alice");
        JsonNode replay = postOk("/api/blend/applications", body, "alice");
        assertEquals(first, replay);
        // 重放不二次预占
        assertEquals("10", sourceView(jia).get("reservedVolume").asText());
        assertEquals("10", sourceView(yi).get("reservedVolume").asText());
    }

    @Test
    void sameRequestIdChangedSourcesOrVolumesReturns409() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");
        String bing = createSource("来源丙", "10", "6");
        String requestId = key("req");
        postOk("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(yi, "10"))), "alice");

        // 改体积 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(yi, "5"))),
                "alice", 409);
        // 改来源（乙换丙）-> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(bing, "10"))),
                "alice", 409);
        // 改操作人 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(yi, "10"))),
                "bob", 409);
        // 改阈值 -> 409
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "p", "4", List.of(item(jia, "10"), item(yi, "10"))),
                "alice", 409);
        // 原预占量不变
        assertEquals("10", sourceView(jia).get("reservedVolume").asText());
        assertEquals("10", sourceView(yi).get("reservedVolume").asText());
        assertEquals("0", sourceView(bing).get("reservedVolume").asText());
    }

    @Test
    void failedFirstAttemptDoesNotOccupyRequestId() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");
        String requestId = key("req");
        // 首次因超阈值失败
        postJson("/api/blend/applications",
                blendBody(requestId, "u", "p", "1", List.of(item(jia, "10"), item(yi, "10"))),
                "alice", 422);
        // 同一 requestId 改以合法参数重试 -> 成功并预占
        JsonNode ok = postOk("/api/blend/applications",
                blendBody(requestId, "u", "p", "3", List.of(item(jia, "10"), item(yi, "10"))),
                "alice");
        assertEquals("RESERVED", ok.get("status").asText());
        assertEquals("10", sourceView(jia).get("reservedVolume").asText());
    }

    @Test
    void sourceCreationIsIdempotentAndKeyReuseConflicts() throws Exception {
        String sourceKey = key("src");
        String commandKey = key("sc");
        Map<String, Object> body = sourceBody(commandKey, sourceKey, "来源丁", "8", "1.5");
        JsonNode first = postOk("/api/blend/sources", body, null);
        JsonNode replay = postOk("/api/blend/sources", body, null);
        assertEquals(first, replay);
        // 同 commandKey 改参 -> 409
        postJson("/api/blend/sources", sourceBody(commandKey, sourceKey, "来源丁", "9", "1.5"),
                null, 409);
        // 换 commandKey 复用 sourceKey -> 409
        postJson("/api/blend/sources", sourceBody(key("sc"), sourceKey, "来源丁", "8", "1.5"),
                null, 409);
        // 非法配额/指标 -> 400
        postJson("/api/blend/sources", sourceBody(key("sc"), key("src"), "x", "0", "1"), null, 400);
        postJson("/api/blend/sources", sourceBody(key("sc"), key("src"), "x", "8", "1.1234567"),
                null, 400);
    }

    // ------------------------------------------------------------------
    // 并发：两个申请争用同一来源余额，已提交预占总额不得超过可用量
    // ------------------------------------------------------------------

    @Test
    void concurrentApplicationsOnOneSourceNeverExceedQuota() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String requestId = key("req");
            futures.add(pool.submit(() -> {
                gate.await();
                return submitOne(requestId, jia, "6", "5");
            }));
        }
        gate.countDown();
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, ok, "配额 10 恰好容纳一笔 6");
        assertEquals(1, rejected, "超额的一笔必须 422");
        assertEquals("6", sourceView(jia).get("reservedVolume").asText());
        assertEquals("4", sourceView(jia).get("availableVolume").asText());
    }

    @Test
    void concurrentApplicationsOnTwoSourcesSettleAtomically() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        String yi = createSource("来源乙", "10", "4");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return submitTwo(key("req"), jia, yi, "6", "6", "4", "alice");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return submitTwo(key("req"), jia, yi, "6", "6", "4", "alice");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 两申请各要甲6乙6，配额各 10：恰好一笔整体成功，另一笔整体 422（不会出现甲扣了乙没扣）
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "s1=" + s1 + " s2=" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        assertEquals("6", sourceView(jia).get("reservedVolume").asText());
        assertEquals("6", sourceView(yi).get("reservedVolume").asText());
        assertEquals("4", sourceView(jia).get("availableVolume").asText());
        assertEquals("4", sourceView(yi).get("availableVolume").asText());
    }

    @Test
    void concurrentApplicationsThatBothFitAreBothAccepted() throws Exception {
        String jia = createSource("来源甲", "10", "2");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return submitOne(key("req"), jia, "4", "5");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return submitOne(key("req"), jia, "6", "5");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 4 + 6 恰好用满 10，两笔都应成功
        assertEquals(200, s1);
        assertEquals(200, s2);
        assertEquals("10", sourceView(jia).get("reservedVolume").asText());
        assertEquals("0", sourceView(jia).get("availableVolume").asText());
    }
}
