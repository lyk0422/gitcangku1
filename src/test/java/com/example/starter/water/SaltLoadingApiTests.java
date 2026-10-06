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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 盐负荷账本 API 测试：田块上限、绑定申请、实灌入账、取消/转让门禁、幂等与并发。
 * 全部使用 H2 内存库（MODE=MySQL）真实事务与行锁，不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SaltLoadingApiTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WaterService waterService;

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

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-salt-" + run + "-" + (++seq));
        body.put("startUtc", "2026-10-01T00:00:00Z");
        body.put("endUtc", "2026-10-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body, null).get("id").asLong();
    }

    private Map<String, Object> plotBody(String requestId, String plotKey, String farmerId, Long limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("plotKey", plotKey);
        body.put("farmerId", farmerId);
        body.put("saltLimitGrams", limit);
        return body;
    }

    private long createPlot(String farmerId, long limitGrams) throws Exception {
        return postOk("/api/plots", plotBody(key("pr"), key("pk"), farmerId, limitGrams), farmerId)
                .get("id").asLong();
    }

    private String submit(long windowId, String userId, String amount, Long plotId, String concentration,
                          String actor) throws Exception {
        String allocationKey = key("ak");
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("plotId", plotId);
        body.put("concentration", concentration);
        postOk("/api/allocations", body, actor);
        return allocationKey;
    }

    private void approve(String allocationKey, int expectedStatus) throws Exception {
        postJson("/api/allocations/" + allocationKey + "/approve",
                Map.of("commandKey", key("ap")), null, expectedStatus);
    }

    private JsonNode allocation(long windowId, String allocationKey) throws Exception {
        JsonNode history = getOk("/api/windows/" + windowId + "/history");
        for (JsonNode node : history.get("allocations")) {
            if (allocationKey.equals(node.get("allocationKey").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("allocation not found: " + allocationKey);
    }

    private Map<String, Object> irrigateBody(String requestId, String volume, long expectedVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("volume", volume);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private JsonNode irrigateOk(String allocationKey, String requestId, String volume, long version,
                                String actor) throws Exception {
        return postOk("/api/allocations/" + allocationKey + "/irrigations",
                irrigateBody(requestId, volume, version), actor);
    }

    private JsonNode plotSalt(long plotId) throws Exception {
        return getOk("/api/plots/" + plotId + "/salt");
    }

    private JsonNode capacity(long windowId) throws Exception {
        return getOk("/api/windows/" + windowId + "/capacity");
    }

    // ------------------------------------------------------------------
    // 题干示例：上限 20 克，10 立方米浓度 2 批准后灌 4，累计 8、预留 12；取消只释放 12
    // ------------------------------------------------------------------

    @Test
    void specExampleCancelReleasesOnlyUnirrigatedSalt() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 20);
        String allocation = submit(windowId, "farmer-1", "10", plotId, "2", "farmer-1");
        approve(allocation, 200);
        assertEquals(1, allocation(windowId, allocation).get("version").asLong());
        JsonNode salt0 = plotSalt(plotId);
        assertEquals("0", salt0.get("cumulativeSaltGrams").asText());
        assertEquals("20", salt0.get("reservedSaltGrams").asText());

        // 实灌 4 立方米：累计 8、预留 12，账本冻结浓度/量/累计
        JsonNode irrigated = irrigateOk(allocation, key("ir"), "4", 1, "farmer-1");
        assertEquals("4", irrigated.get("volume").asText());
        assertEquals("4", irrigated.get("irrigatedAmount").asText());
        assertEquals("8", irrigated.get("saltGrams").asText());
        assertEquals("8", irrigated.get("cumulativeSaltGrams").asText());
        assertEquals(2, irrigated.get("version").asLong());
        JsonNode salt1 = plotSalt(plotId);
        assertEquals("8", salt1.get("cumulativeSaltGrams").asText());
        assertEquals("12", salt1.get("reservedSaltGrams").asText());
        assertEquals("0", salt1.get("remainingSaltGrams").asText());
        assertEquals(1, salt1.get("ledger").size());
        JsonNode entry = salt1.get("ledger").get(0);
        assertEquals("2", entry.get("concentration").asText());
        assertEquals("4", entry.get("volume").asText());
        assertEquals("8", entry.get("saltGrams").asText());
        assertEquals("8", entry.get("cumulativeSaltGrams").asText());

        // 取消只释放未灌的 12 克与相应水额度；已灌 8 克留在账本，4 立方米水额度继续占用
        JsonNode cancelled = postOk("/api/allocations/" + allocation + "/cancel",
                Map.of("commandKey", key("cc")), "farmer-1");
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("4", cancelled.get("heldAmount").asText());
        JsonNode salt2 = plotSalt(plotId);
        assertEquals("8", salt2.get("cumulativeSaltGrams").asText());
        assertEquals("0", salt2.get("reservedSaltGrams").asText());
        assertEquals("12", salt2.get("remainingSaltGrams").asText());
        assertEquals(1, salt2.get("ledger").size(), "额度释放不改历史账本");
        assertEquals("4", capacity(windowId).get("approvedTotal").asText());

        // 随后 7 立方米同浓度申请：8 + 14 = 22 > 20 -> 422 超限拒绝
        String tooMuch = submit(windowId, "farmer-1", "7", plotId, "2", "farmer-1");
        approve(tooMuch, 422);
        // 6 立方米同浓度：8 + 12 = 20，恰好不超限 -> 200
        String fits = submit(windowId, "farmer-1", "6", plotId, "2", "farmer-1");
        approve(fits, 200);
        JsonNode salt3 = plotSalt(plotId);
        assertEquals("8", salt3.get("cumulativeSaltGrams").asText());
        assertEquals("12", salt3.get("reservedSaltGrams").asText());
    }

    // ------------------------------------------------------------------
    // 田块创建：参数、归属与幂等
    // ------------------------------------------------------------------

    @Test
    void createPlotValidationOwnershipAndIdempotency() throws Exception {
        // 上限必须为正整数
        postJson("/api/plots", plotBody(key("pr"), key("pk"), "farmer-1", 0L), "farmer-1", 400);
        postJson("/api/plots", plotBody(key("pr"), key("pk"), "farmer-1", -5L), "farmer-1", 400);
        Map<String, Object> noLimit = plotBody(key("pr"), key("pk"), "farmer-1", null);
        noLimit.remove("saltLimitGrams");
        postJson("/api/plots", noLimit, "farmer-1", 400);
        // 操作人须为农户本人 -> 403；缺请求头 -> 400
        postJson("/api/plots", plotBody(key("pr"), key("pk"), "farmer-1", 10L), "farmer-2", 403);
        postJson("/api/plots", plotBody(key("pr"), key("pk"), "farmer-1", 10L), null, 400);

        // 正常创建 + 同 requestId 同参重放返回首次完整结果
        String requestId = key("pr");
        String plotKey = key("pk");
        Map<String, Object> body = plotBody(requestId, plotKey, "farmer-1", 20L);
        JsonNode first = postOk("/api/plots", body, "farmer-1");
        assertEquals(20, first.get("saltLimitGrams").asLong());
        JsonNode replay = postOk("/api/plots", body, "farmer-1");
        assertEquals(first, replay);
        // 同 requestId 改参 -> 409
        postJson("/api/plots", plotBody(requestId, plotKey, "farmer-1", 30L), "farmer-1", 409);
        // 换 requestId 复用 plotKey -> 409
        postJson("/api/plots", plotBody(key("pr"), plotKey, "farmer-1", 20L), "farmer-1", 409);
        // 田块不存在 -> 404
        mvc.perform(get("/api/plots/999999999/salt")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // 绑定申请校验
    // ------------------------------------------------------------------

    @Test
    void submitBindingValidation() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 20);
        long otherPlot = createPlot("farmer-2", 20);

        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        body.put("windowId", windowId);
        body.put("userId", "farmer-1");
        body.put("amount", "1");
        // 只给 plotId 或只给浓度 -> 400
        body.put("plotId", plotId);
        postJson("/api/allocations", body, "farmer-1", 400);
        body.remove("plotId");
        body.put("concentration", "2");
        postJson("/api/allocations", body, "farmer-1", 400);
        // 浓度非法：超过 3 位小数 / 负数 / 非数字 -> 400
        body.put("plotId", plotId);
        body.put("concentration", "1.0001");
        postJson("/api/allocations", body, "farmer-1", 400);
        body.put("concentration", "-1");
        postJson("/api/allocations", body, "farmer-1", 400);
        body.put("concentration", "abc");
        postJson("/api/allocations", body, "farmer-1", 400);
        // 田块不存在 -> 404
        body.put("concentration", "2");
        body.put("plotId", 999999999L);
        postJson("/api/allocations", body, "farmer-1", 404);
        // 田块属于其他农户 -> 403
        body.put("plotId", otherPlot);
        postJson("/api/allocations", body, "farmer-1", 403);
        // 浓度允许为 0：绑定成功且批准不耗盐预算
        body.put("plotId", plotId);
        body.put("concentration", "0");
        body.put("commandKey", key("ac"));
        body.put("allocationKey", key("ak"));
        JsonNode created = postOk("/api/allocations", body, "farmer-1");
        assertEquals(plotId, created.get("plotId").asLong());
        assertEquals("0", created.get("concentration").asText());
        assertEquals("0", created.get("irrigatedAmount").asText());
        approve(created.get("allocationKey").asText(), 200);
        assertEquals("0", plotSalt(plotId).get("reservedSaltGrams").asText());
    }

    // ------------------------------------------------------------------
    // 实灌失败分支与失败不占键
    // ------------------------------------------------------------------

    @Test
    void irrigateFailureBranches() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 100);
        String allocation = submit(windowId, "farmer-1", "10", plotId, "2", "farmer-1");

        // 未批准 -> 409
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "1", 0), "farmer-1", 409);
        approve(allocation, 200);
        // 非申请人 -> 403
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "1", 1), "farmer-2", 403);
        // 期望版本不符 -> 409
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "1", 99), "farmer-1", 409);
        // 水量非法 -> 400
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "0", 1), "farmer-1", 400);
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "1.0001", 1), "farmer-1", 400);
        // 缺 expectedVersion -> 400
        Map<String, Object> noVersion = irrigateBody(key("ir"), "1", 1);
        noVersion.remove("expectedVersion");
        postJson("/api/allocations/" + allocation + "/irrigations", noVersion, "farmer-1", 400);
        // 申请不存在 -> 404
        postJson("/api/allocations/no-such-" + run + "/irrigations",
                irrigateBody(key("ir"), "1", 0), "farmer-1", 404);

        // 超过未灌量 -> 422，且失败不占 requestId：同键修正参数后成功
        String requestId = key("ir");
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(requestId, "10.001", 1), "farmer-1", 422);
        JsonNode ok = irrigateOk(allocation, requestId, "10", 1, "farmer-1");
        assertEquals("20", ok.get("saltGrams").asText());
        assertEquals("20", plotSalt(plotId).get("cumulativeSaltGrams").asText());
        // 已灌满后未灌量为 0，再灌 -> 422
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(key("ir"), "0.001", 2), "farmer-1", 422);
    }

    // ------------------------------------------------------------------
    // 实灌幂等：同参重放、改参 409
    // ------------------------------------------------------------------

    @Test
    void irrigateIdempotencyReplayAndConflict() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 100);
        String allocation = submit(windowId, "farmer-1", "10", plotId, "2", "farmer-1");
        approve(allocation, 200);

        String requestId = key("ir");
        JsonNode first = irrigateOk(allocation, requestId, "4", 1, "farmer-1");
        JsonNode replay = irrigateOk(allocation, requestId, "4", 1, "farmer-1");
        assertEquals(first, replay);
        assertEquals(1, plotSalt(plotId).get("ledger").size(), "重放不重复入账");
        assertEquals("4", allocation(windowId, allocation).get("irrigatedAmount").asText());
        // 同 requestId 改水量或改期望版本 -> 409
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(requestId, "5", 1), "farmer-1", 409);
        postJson("/api/allocations/" + allocation + "/irrigations",
                irrigateBody(requestId, "4", 2), "farmer-1", 409);
        // 十进制按值比较：4.0 与 4 视为同参，重放首次结果
        JsonNode replayDecimal = irrigateOk(allocation, requestId, "4.0", 1, "farmer-1");
        assertEquals(first, replayDecimal);
        assertEquals(1, plotSalt(plotId).get("ledger").size());
    }

    // ------------------------------------------------------------------
    // 转让门禁：已灌部分不能转让，两侧盐预算重新核对
    // ------------------------------------------------------------------

    @Test
    void irrigatedPartCannotBeTransferredOrReleased() throws Exception {
        long windowId = createWindow("100");
        long plotA = createPlot("farmer-1", 100);
        long plotB = createPlot("farmer-2", 100);
        String source = submit(windowId, "farmer-1", "10", plotA, "2", "farmer-1");
        approve(source, 200);
        irrigateOk(source, key("ir"), "4", 1, "farmer-1");

        // 未灌额度仅 6，目标 7 立方米 -> 422
        String target7 = submit(windowId, "farmer-2", "7", plotB, "1", "farmer-2");
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, target7), "farmer-1", 422);
        // 目标 6 立方米 -> 200，源持有降为 4（等于已灌量，不可再转）
        String target6 = submit(windowId, "farmer-2", "6", plotB, "1", "farmer-2");
        postOk("/api/transfers", transferBody(key("tc"), key("tk"), source, target6), "farmer-1");
        assertEquals("4", allocation(windowId, source).get("heldAmount").asText());
        String target1 = submit(windowId, "farmer-2", "1", plotB, "1", "farmer-2");
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, target1), "farmer-1", 422);

        // 取消源：已灌 4 立方米水额度继续占用，8 克盐留在账本
        postOk("/api/allocations/" + source + "/cancel", Map.of("commandKey", key("cc")), "farmer-1");
        assertEquals("4", allocation(windowId, source).get("heldAmount").asText());
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
        JsonNode saltA = plotSalt(plotA);
        assertEquals("8", saltA.get("cumulativeSaltGrams").asText());
        assertEquals("0", saltA.get("reservedSaltGrams").asText());
        assertEquals("6", plotSalt(plotB).get("reservedSaltGrams").asText());
    }

    @Test
    void transferRechecksSaltBudgetWithOwnConcentrations() throws Exception {
        long windowId = createWindow("100");
        long plotA = createPlot("farmer-1", 100);
        long plotB = createPlot("farmer-2", 15);
        String source = submit(windowId, "farmer-1", "10", plotA, "1", "farmer-1");
        approve(source, 200);

        // 目标浓度 5：4 立方米 = 20 克 > 上限 15 -> 422，不按来源浓度 1 推断
        String saltyTarget = submit(windowId, "farmer-2", "4", plotB, "5", "farmer-2");
        postJson("/api/transfers", transferBody(key("tc"), key("tk"), source, saltyTarget),
                "farmer-1", 422);
        assertEquals(0, getOk("/api/windows/" + windowId + "/transfers").get("transfers").size());
        // 目标浓度 2：4 立方米 = 8 克 <= 15 -> 200，两侧预留按各自浓度重算
        String okTarget = submit(windowId, "farmer-2", "4", plotB, "2", "farmer-2");
        postOk("/api/transfers", transferBody(key("tc"), key("tk"), source, okTarget), "farmer-1");
        assertEquals("8", plotSalt(plotB).get("reservedSaltGrams").asText());
        assertEquals("6", plotSalt(plotA).get("reservedSaltGrams").asText());
        assertEquals("10", capacity(windowId).get("approvedTotal").asText());
    }

    private Map<String, Object> transferBody(String commandKey, String transferKey,
                                             String sourceKey, String targetKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", commandKey);
        body.put("transferKey", transferKey);
        body.put("sourceAllocationKey", sourceKey);
        body.put("targetAllocationKey", targetKey);
        return body;
    }

    // ------------------------------------------------------------------
    // 并发：同一田块批准不超盐上限；同 requestId 实灌只入账一次
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovalsOnSamePlotNeverExceedSaltLimit() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 20);
        String a1 = submit(windowId, "farmer-1", "6", plotId, "2", "farmer-1");
        String a2 = submit(windowId, "farmer-1", "6", plotId, "2", "farmer-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), a1);
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return approveStatus(key("ap"), a2);
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 各 12 克，上限 20：恰好一个批准成功，另一个 422
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "approve: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 422 ? 1 : 0) + (s2 == 422 ? 1 : 0));
        JsonNode salt = plotSalt(plotId);
        assertEquals("12", salt.get("reservedSaltGrams").asText());
        assertTrue(Double.parseDouble(salt.get("cumulativeSaltGrams").asText())
                + Double.parseDouble(salt.get("reservedSaltGrams").asText()) <= 20.0);
    }

    @Test
    void concurrentIrrigationsSameRequestIdSettleExactlyOnce() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 100);
        String allocation = submit(windowId, "farmer-1", "10", plotId, "2", "farmer-1");
        approve(allocation, 200);
        String requestId = key("ir");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return irrigateStatus(requestId, allocation, "4", 1, "farmer-1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return irrigateStatus(requestId, allocation, "4", 1, "farmer-1");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 竞争不报 500；同键同参至多一次入账，重放或 409 提示重试
        assertNotEquals(500, s1);
        assertNotEquals(500, s2);
        assertTrue(s1 == 200 || s2 == 200, "至少一次成功: " + s1 + "/" + s2);
        assertEquals(1, plotSalt(plotId).get("ledger").size());
        assertEquals("4", allocation(windowId, allocation).get("irrigatedAmount").asText());
        assertEquals("8", plotSalt(plotId).get("cumulativeSaltGrams").asText());
    }

    @Test
    void concurrentIrrigationsDifferentRequestIdsConflictOnVersion() throws Exception {
        long windowId = createWindow("100");
        long plotId = createPlot("farmer-1", 100);
        String allocation = submit(windowId, "farmer-1", "10", plotId, "2", "farmer-1");
        approve(allocation, 200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            gate.await();
            return irrigateStatus(key("ir"), allocation, "4", 1, "farmer-1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            gate.await();
            return irrigateStatus(key("ir"), allocation, "4", 1, "farmer-1");
        });
        gate.countDown();
        int s1 = f1.get(30, TimeUnit.SECONDS);
        int s2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 相同期望版本并发：恰好一个成功，另一个 409 版本冲突，只入账一笔
        assertEquals(1, (s1 == 200 ? 1 : 0) + (s2 == 200 ? 1 : 0), "irrigate: " + s1 + "/" + s2);
        assertEquals(1, (s1 == 409 ? 1 : 0) + (s2 == 409 ? 1 : 0));
        assertEquals(1, plotSalt(plotId).get("ledger").size());
        assertEquals("4", allocation(windowId, allocation).get("irrigatedAmount").asText());
    }

    private int approveStatus(String commandKey, String allocationKey) {
        try {
            waterService.approveAllocation(commandKey, allocationKey);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    private int irrigateStatus(String requestId, String allocationKey, String volume,
                               long expectedVersion, String actor) {
        try {
            waterService.irrigate(requestId, allocationKey, volume, expectedVersion, actor);
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        }
    }

    // ------------------------------------------------------------------
    // 旧流程：未绑定申请不受影响，实灌可用但不入盐账本
    // ------------------------------------------------------------------

    @Test
    void unboundAllocationKeepsLegacyFlow() throws Exception {
        long windowId = createWindow("10");
        String allocation = submit(windowId, "user-1", "3", null, null, "alice");
        JsonNode created = allocation(windowId, allocation);
        assertTrue(created.get("plotId").isNull());
        assertTrue(created.get("concentration").isNull());
        assertEquals("0", created.get("irrigatedAmount").asText());
        assertEquals(0, created.get("version").asLong());
        approve(allocation, 200);
        // 未绑定申请也可实灌：只记水量，不产生盐量
        JsonNode irrigated = irrigateOk(allocation, key("ir"), "1", 1, "alice");
        assertTrue(irrigated.get("saltGrams").isNull());
        assertTrue(irrigated.get("cumulativeSaltGrams").isNull());
        assertEquals("1", irrigated.get("irrigatedAmount").asText());
        // 取消后已灌 1 立方米水额度继续占用，未灌 2 立方米释放
        postOk("/api/allocations/" + allocation + "/cancel",
                Map.of("commandKey", key("cc")), "alice");
        assertEquals("1", allocation(windowId, allocation).get("heldAmount").asText());
        assertEquals("1", capacity(windowId).get("approvedTotal").asText());
    }
}
