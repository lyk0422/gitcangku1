package com.example.starter.water;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多水源重平衡 API + 真实 H2（MODE=MySQL）集成测试：守恒闭环、供给上限、已核销量、
 * 整体回滚、幂等与并发提交边界。所有矩阵约束、版本与事务均由真实数据库验证，不 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RebalanceApiTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RebalanceService rebalanceService;
    @Autowired
    private JdbcTemplate jdbc;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;

    private String key(String prefix) {
        return prefix + "-" + run + "-" + (++seq);
    }

    // ------------------------------------------------------------------
    // HTTP / 数据构造辅助
    // ------------------------------------------------------------------

    private MvcResult postJson(String url, Object body, String actor, int expected) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (actor != null) {
            request.header("X-Actor-Id", actor);
        }
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    private JsonNode postOk(String url, Object body) throws Exception {
        return objectMapper.readTree(postJson(url, body, null, 200).getResponse().getContentAsString());
    }

    private JsonNode postOk(String url, Object body, String actor) throws Exception {
        return objectMapper.readTree(postJson(url, body, actor, 200).getResponse().getContentAsString());
    }

    private JsonNode getOk(String url) throws Exception {
        return objectMapper.readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private long createWindow(String planned) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("wc"));
        body.put("windowKey", key("wk"));
        body.put("channelId", "ch-rb-" + run + "-" + seq);
        body.put("startUtc", "2026-12-01T00:00:00Z");
        body.put("endUtc", "2026-12-01T02:00:00Z");
        body.put("plannedVolume", planned);
        return postOk("/api/windows", body).get("id").asLong();
    }

    /** 配置 n 个水源 s1..sn，供给上限均为 cap。 */
    private void configureSources(long windowId, int n, String cap) throws Exception {
        List<Map<String, String>> sources = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            sources.add(Map.of("sourceId", "s" + i, "supplyCap", cap));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("sc"));
        body.put("sources", sources);
        postOk("/api/windows/" + windowId + "/sources", body);
    }

    /** 登记区块：适用 applicable 水源，初始额度由 initial（保持插入顺序）给出。 */
    private void configureBlock(long windowId, String blockId, List<String> applicable,
                                Map<String, String> initial) throws Exception {
        List<Map<String, String>> quotas = new ArrayList<>();
        for (Map.Entry<String, String> e : initial.entrySet()) {
            quotas.add(Map.of("sourceId", e.getKey(), "quota", e.getValue()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("commandKey", key("bc"));
        body.put("blockId", blockId);
        body.put("applicableSources", applicable);
        body.put("quotas", quotas);
        postOk("/api/windows/" + windowId + "/blocks", body);
    }

    private Map<String, String> initial(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private Map<String, Object> detail(String block, String from, String to, String volume) {
        Map<String, Object> d = new HashMap<>();
        d.put("blockId", block);
        d.put("sourceSourceId", from);
        d.put("targetSourceId", to);
        d.put("volume", volume);
        return d;
    }

    /** 读取某窗口当前全部单元格版本（key = block/source）。 */
    private Map<String, Long> versions(long windowId) throws Exception {
        Map<String, Long> versions = new LinkedHashMap<>();
        JsonNode blocks = getOk("/api/windows/" + windowId + "/blocks");
        for (JsonNode block : blocks) {
            for (JsonNode cell : block.get("cells")) {
                versions.put(block.get("blockId").asText() + "/" + cell.get("sourceId").asText(),
                        cell.get("version").asLong());
            }
        }
        return versions;
    }

    private List<Map<String, Object>> expectedVersions(Map<String, Long> all, String... keys) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String k : keys) {
            String[] parts = k.split("/");
            list.add(Map.of("blockId", parts[0], "sourceId", parts[1],
                    "expectedVersion", all.get(k)));
        }
        return list;
    }

    private Map<String, Object> activateBody(String requestId, String rebalanceKey, long windowId,
                                             List<Map<String, Object>> details,
                                             List<Map<String, Object>> versions) {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", requestId);
        body.put("rebalanceKey", rebalanceKey);
        body.put("windowId", windowId);
        body.put("details", details);
        body.put("expectedVersions", versions);
        return body;
    }

    private Map<String, Object> previewBody(long windowId, List<Map<String, Object>> details) {
        Map<String, Object> body = new HashMap<>();
        body.put("windowId", windowId);
        body.put("details", details);
        return body;
    }

    private JsonNode cellOf(JsonNode blocks, String block, String source) {
        for (JsonNode b : blocks) {
            if (block.equals(b.get("blockId").asText())) {
                for (JsonNode c : b.get("cells")) {
                    if (source.equals(c.get("sourceId").asText())) {
                        return c;
                    }
                }
            }
        }
        throw new IllegalStateException("cell not found " + block + "/" + source);
    }

    private BigDecimal dbQuota(long windowId, String block, String source) {
        return jdbc.queryForObject(
                "SELECT quota FROM block_quota WHERE window_id = ? AND block_id = ? AND source_id = ?",
                BigDecimal.class, windowId, block, source);
    }

    // ------------------------------------------------------------------
    // 主流程：三水源守恒闭环
    // ------------------------------------------------------------------

    @Test
    void activateClosedLoopConservesAndFreezesEvidence() throws Exception {
        long windowId = createWindow("100");
        configureSources(windowId, 3, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "0"));
        configureBlock(windowId, "b2", List.of("s2", "s3"), initial("s2", "6", "s3", "0"));
        configureBlock(windowId, "b3", List.of("s3", "s1"), initial("s3", "6", "s1", "0"));

        Map<String, Long> v0 = versions(windowId);
        // 故意打乱顺序 + 拆分重复明细，验证规范化求和与换序等价
        String requestId = key("req");
        String rebalanceKey = key("rb");
        List<Map<String, Object>> details = List.of(
                detail("b3", "s3", "s1", "1.500"),
                detail("b1", "s1", "s2", "1"),
                detail("b2", "s2", "s3", "3"),
                detail("b3", "s3", "s1", "1.500"),
                detail("b1", "s1", "s2", "2"));
        List<Map<String, Object>> ev = expectedVersions(v0,
                "b1/s1", "b1/s2", "b2/s2", "b2/s3", "b3/s3", "b3/s1");

        JsonNode result = postOk("/api/rebalances/activate",
                activateBody(requestId, rebalanceKey, windowId, details, ev));
        assertEquals(rebalanceKey, result.get("rebalanceKey").asText());
        assertEquals("ACTIVE", result.get("status").asText());
        // 规范化后明细：b1 s1->s2 合并为 3，b3 s3->s1 合并为 3；按区块排序
        ArrayNode norm = (ArrayNode) result.get("details");
        assertEquals(3, norm.size());
        assertEquals("3", norm.get(0).get("volume").asText()); // b1 s1->s2 = 1+2
        assertEquals("3", norm.get(2).get("volume").asText()); // b3 s3->s1 = 1.5+1.5

        // 区块总额度守恒、各格后态
        JsonNode blocks = getOk("/api/windows/" + windowId + "/blocks");
        assertEquals("3", cellOf(blocks, "b1", "s1").get("quota").asText());
        assertEquals("3", cellOf(blocks, "b1", "s2").get("quota").asText());
        assertEquals("3", cellOf(blocks, "b2", "s2").get("quota").asText());
        assertEquals("3", cellOf(blocks, "b2", "s3").get("quota").asText());
        assertEquals("3", cellOf(blocks, "b3", "s3").get("quota").asText());
        assertEquals("3", cellOf(blocks, "b3", "s1").get("quota").asText());
        // 涉及单元格逐记录增版
        assertEquals(1L, cellOf(blocks, "b1", "s1").get("version").asLong());

        // 证据：前后矩阵均按 sourceId、区块稳定排序，且冻结供给上限与核销量
        JsonNode evidence = getOk("/api/rebalances/" + rebalanceKey + "/evidence");
        ArrayNode before = (ArrayNode) evidence.get("beforeMatrix");
        ArrayNode after = (ArrayNode) evidence.get("afterMatrix");
        assertEquals(6, before.size());
        assertEquals("s1", before.get(0).get("sourceId").asText());
        assertEquals("20", before.get(0).get("supplyCap").asText());
        assertEquals("0", before.get(0).get("consumed").asText());
        // 精确按 (source, block) 找到 b1/s1
        JsonNode beforeB1S1 = findMatrix(before, "b1", "s1");
        JsonNode afterB1S1 = findMatrix(after, "b1", "s1");
        assertEquals("6", beforeB1S1.get("quota").asText());
        assertEquals("3", afterB1S1.get("quota").asText());
        assertEquals(1L, afterB1S1.get("version").asLong());
        assertEquals(0L, beforeB1S1.get("version").asLong());
    }

    private JsonNode findMatrix(ArrayNode matrix, String block, String source) {
        for (JsonNode cell : matrix) {
            if (block.equals(cell.get("blockId").asText()) && source.equals(cell.get("sourceId").asText())) {
                return cell;
            }
        }
        throw new IllegalStateException("matrix cell not found " + block + "/" + source);
    }

    // ------------------------------------------------------------------
    // 幂等：同参重放 / 异参 409 / 键唯一 / 失败不占键
    // ------------------------------------------------------------------

    @Test
    void requestIdReplayEquivalentAndDifferentParamsConflict() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 2, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "8", "s2", "0"));
        Map<String, Long> v0 = versions(windowId);
        String requestId = key("req");
        String rebalanceKey = key("rb");

        List<Map<String, Object>> body1 = List.of(detail("b1", "s1", "s2", "1"), detail("b1", "s2", "s1", "0.5"));
        JsonNode first = postOk("/api/rebalances/activate",
                activateBody(requestId, rebalanceKey, windowId, body1,
                        expectedVersions(v0, "b1/s1", "b1/s2")));

        // 同 requestId/rebalanceKey，但明细换序且 1 拆成 0.5+0.5（规范化等价）-> 重放首次快照
        List<Map<String, Object>> body2 = List.of(
                detail("b1", "s2", "s1", "0.5"),
                detail("b1", "s1", "s2", "0.5"),
                detail("b1", "s1", "s2", "0.5"));
        JsonNode replay = postOk("/api/rebalances/activate",
                activateBody(requestId, rebalanceKey, windowId, body2,
                        expectedVersions(versions(windowId), "b1/s1", "b1/s2")));
        assertEquals(first.get("details"), replay.get("details"));
        assertEquals(first.get("afterMatrix"), replay.get("afterMatrix"));
        // 只生成一单、只搬了一次水（s1: 8-1+0.5=7.5）
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rebalance_order WHERE window_id = ?", Integer.class, windowId);
        assertEquals(1, orderCount);
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("7.5")));

        // 同 requestId 异参 -> 409
        List<Map<String, Object>> different = List.of(
                detail("b1", "s1", "s2", "2"), detail("b1", "s2", "s1", "0.5"));
        postJson("/api/rebalances/activate",
                activateBody(requestId, key("rb2"), windowId, different,
                        expectedVersions(versions(windowId), "b1/s1", "b1/s2")), null, 409);
        // 已用 rebalanceKey 换新 requestId 复用 -> 409
        postJson("/api/rebalances/activate",
                activateBody(key("req2"), rebalanceKey, windowId, body1,
                        expectedVersions(versions(windowId), "b1/s1", "b1/s2")), null, 409);
    }

    @Test
    void failedActivationDoesNotOccupyRequestIdAndLeavesMatrixIntact() throws Exception {
        // s2 供给上限更紧（5）：用仓储直接建一个 s2=5 的窗口矩阵
        long tight = createWindow("50");
        jdbc.update("INSERT INTO water_source (window_id, source_id, supply_cap, created_nanos) VALUES"
                + " (?, 's1', 20, 1), (?, 's2', 5, 1)", tight, tight);
        configureBlock(tight, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "4"));
        Map<String, Long> v0 = versions(tight);
        String requestId = key("req");
        // 净搬 1.5 到 s2 -> 后态 5.5 > 上限 5，整单 422
        List<Map<String, Object>> overCap = List.of(
                detail("b1", "s1", "s2", "2"), detail("b1", "s2", "s1", "0.5"));
        postJson("/api/rebalances/activate",
                activateBody(requestId, key("rb"), tight, overCap,
                        expectedVersions(v0, "b1/s1", "b1/s2")), null, 422);

        // 失败不占键：同 requestId 改用合法参数（净搬 1，s2 后态恰好 5）成功
        List<Map<String, Object>> legal = List.of(
                detail("b1", "s1", "s2", "1.5"), detail("b1", "s2", "s1", "0.5"));
        JsonNode ok = postOk("/api/rebalances/activate",
                activateBody(requestId, key("rb2"), tight, legal,
                        expectedVersions(v0, "b1/s1", "b1/s2")));
        assertNotNull(ok.get("rebalanceKey"));
        // 失败整单回滚：矩阵最终只有合法单的效果（s1: 6-1.5+0.5=5, s2: 5）
        assertEquals(0, dbQuota(tight, "b1", "s1").compareTo(new BigDecimal("5")));
        assertEquals(0, dbQuota(tight, "b1", "s2").compareTo(new BigDecimal("5")));
    }

    // ------------------------------------------------------------------
    // 失败分支：已核销量、窗口关闭、版本变化、适用性
    // ------------------------------------------------------------------

    @Test
    void consumedWaterCannotBeMovedAndOrderRollsBack() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 2, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "5", "s2", "5"));
        // 在 s1 核销 4（操作人 alice）
        Map<String, Object> writeoff = new HashMap<>();
        writeoff.put("commandKey", key("wo"));
        writeoff.put("writeoffKey", key("wk"));
        writeoff.put("sourceId", "s1");
        writeoff.put("volume", "4");
        JsonNode wo = postOk("/api/windows/" + windowId + "/blocks/b1/writeoff", writeoff, "alice");
        assertEquals("4", wo.get("consumedAfter").asText());

        Map<String, Long> v1 = versions(windowId);
        // 净搬走 3（3 出 0.5 回）使 s1 后态 2.5 < consumed 4 -> 422 整单回滚
        List<Map<String, Object>> tooMuch = List.of(
                detail("b1", "s1", "s2", "3"), detail("b1", "s2", "s1", "0.5"));
        postJson("/api/rebalances/activate",
                activateBody(key("req"), key("rb"), windowId, tooMuch,
                        expectedVersions(v1, "b1/s1", "b1/s2")), null, 422);
        // 矩阵不变
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("5")));
        BigDecimal consumed = jdbc.queryForObject(
                "SELECT consumed FROM block_quota WHERE window_id=? AND block_id='b1' AND source_id='s1'",
                BigDecimal.class, windowId);
        assertEquals(0, consumed.compareTo(new BigDecimal("4")));

        // 只搬未核销部分（净 1，s1 后态 4 == consumed）成功
        List<Map<String, Object>> legal = List.of(
                detail("b1", "s1", "s2", "2"), detail("b1", "s2", "s1", "1"));
        postOk("/api/rebalances/activate",
                activateBody(key("req2"), key("rb2"), windowId, legal,
                        expectedVersions(v1, "b1/s1", "b1/s2")));
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("4")));
        assertEquals(0, dbQuota(windowId, "b1", "s2").compareTo(new BigDecimal("6")));
        // 核销量在重平衡后保持 4
        BigDecimal consumedAfter = jdbc.queryForObject(
                "SELECT consumed FROM block_quota WHERE window_id=? AND block_id='b1' AND source_id='s1'",
                BigDecimal.class, windowId);
        assertEquals(0, consumedAfter.compareTo(new BigDecimal("4")));
    }

    @Test
    void closedWindowRejectsWholeOrder() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 2, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "0"));
        postOk("/api/windows/" + windowId + "/close", Map.of("commandKey", key("close")));
        Map<String, Long> v0 = versions(windowId);
        List<Map<String, Object>> details = List.of(
                detail("b1", "s1", "s2", "1"), detail("b1", "s2", "s1", "0.5"));
        postJson("/api/rebalances/activate",
                activateBody(key("req"), key("rb"), windowId, details,
                        expectedVersions(v0, "b1/s1", "b1/s2")), null, 409);
        // 矩阵不变
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("6")));
    }

    @Test
    void staleExpectedVersionConflicts() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 2, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "8", "s2", "0"));
        Map<String, Long> v0 = versions(windowId);
        List<Map<String, Object>> details = List.of(
                detail("b1", "s1", "s2", "1"), detail("b1", "s2", "s1", "0.5"));
        // 第一单成功，版本递增
        postOk("/api/rebalances/activate",
                activateBody(key("req"), key("rb"), windowId, details,
                        expectedVersions(v0, "b1/s1", "b1/s2")));
        // 第二单仍带旧版本 0 -> 409 整单失败
        postJson("/api/rebalances/activate",
                activateBody(key("req2"), key("rb2"), windowId, details,
                        expectedVersions(v0, "b1/s1", "b1/s2")), null, 409);
        // 仅第一单生效
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("7.5")));
    }

    @Test
    void targetSourceNotApplicableConflicts() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 3, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "0"));
        Map<String, Long> v0 = versions(windowId);
        // 目标 s3 不适用于 b1
        List<Map<String, Object>> details = List.of(
                detail("b1", "s1", "s3", "1"), detail("b1", "s3", "s1", "0.5"));
        postJson("/api/rebalances/activate",
                activateBody(key("req"), key("rb"), windowId, details,
                        expectedVersions(v0, "b1/s1", "b1/s2")), null, 409);
    }

    // ------------------------------------------------------------------
    // 预览不落库
    // ------------------------------------------------------------------

    @Test
    void previewComputesAfterMatrixWithoutMutation() throws Exception {
        long windowId = createWindow("50");
        configureSources(windowId, 2, "20");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "4"));
        List<Map<String, Object>> details = List.of(
                detail("b1", "s1", "s2", "2"), detail("b1", "s2", "s1", "0.5"));
        JsonNode preview = postOk("/api/rebalances/preview", previewBody(windowId, details));
        assertTrue(preview.get("blockTotalsConserved").asBoolean());
        assertTrue(preview.get("valid").asBoolean());
        JsonNode afterB1S1 = findMatrix((ArrayNode) preview.get("afterMatrix"), "b1", "s1");
        assertEquals("4.5", afterB1S1.get("quota").asText()); // 6 - 2 + 0.5
        // 预览不落库、不建单
        assertEquals(0, dbQuota(windowId, "b1", "s1").compareTo(new BigDecimal("6")));
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rebalance_order WHERE window_id=?", Integer.class, windowId);
        assertEquals(0, orders);
    }

    // ------------------------------------------------------------------
    // 并发：两个重平衡按提交顺序，只能观察到完整旧态或新态
    // ------------------------------------------------------------------

    @Test
    void concurrentRebalancesSerializeByCommitOrder() throws Exception {
        long windowId = createWindow("100");
        configureSources(windowId, 2, "50");
        configureBlock(windowId, "b1", List.of("s1", "s2"), initial("s1", "6", "s2", "0"));
        configureBlock(windowId, "b2", List.of("s1", "s2"), initial("s2", "6", "s1", "0"));
        Map<String, Long> v0 = versions(windowId);

        List<Map<String, Object>> planA = List.of(
                detail("b1", "s1", "s2", "2"), detail("b2", "s2", "s1", "2"));
        List<Map<String, Object>> planB = List.of(
                detail("b1", "s1", "s2", "1"), detail("b2", "s2", "s1", "1"));
        List<Map<String, Object>> ev = expectedVersions(v0, "b1/s1", "b1/s2", "b2/s1", "b2/s2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> activateAsync(gate,
                activateBody(key("reqA"), key("rbA"), windowId, planA, ev)));
        Future<Integer> fb = pool.submit(() -> activateAsync(gate,
                activateBody(key("reqB"), key("rbB"), windowId, planB, ev)));
        gate.countDown();
        int statusA = fa.get(30, TimeUnit.SECONDS);
        int statusB = fb.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 恰好一单成功，另一单因窗口锁内版本变化 409
        assertEquals(1, (statusA == 200 ? 1 : 0) + (statusB == 200 ? 1 : 0),
                "statusA=" + statusA + " statusB=" + statusB);
        int loser = statusA == 200 ? statusB : statusA;
        assertEquals(409, loser);

        // 最终矩阵恰好等于先提交那一单的完整新态（A: b1s1=4/b2s2=4，B: b1s1=5/b2s2=5）
        BigDecimal b1s1 = dbQuota(windowId, "b1", "s1");
        BigDecimal b2s2 = dbQuota(windowId, "b2", "s2");
        if (statusA == 200) {
            assertEquals(0, b1s1.compareTo(new BigDecimal("4")));
            assertEquals(0, b2s2.compareTo(new BigDecimal("4")));
        } else {
            assertEquals(0, b1s1.compareTo(new BigDecimal("5")));
            assertEquals(0, b2s2.compareTo(new BigDecimal("5")));
        }
        // 区块守恒
        assertEquals(0, dbQuota(windowId, "b1", "s1").add(dbQuota(windowId, "b1", "s2"))
                .compareTo(new BigDecimal("6")));
        assertEquals(0, dbQuota(windowId, "b2", "s1").add(dbQuota(windowId, "b2", "s2"))
                .compareTo(new BigDecimal("6")));
        // 只有一单落库
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rebalance_order WHERE window_id=?", Integer.class, windowId);
        assertEquals(1, orders);
    }

    private int activateAsync(CountDownLatch gate, Map<String, Object> body) {
        try {
            gate.await();
            rebalanceService.activate(objectMapper.convertValue(body,
                    com.example.starter.water.dto.Dtos.RebalanceActivateRequest.class));
            return 200;
        } catch (ApiException e) {
            return e.status().value();
        } catch (Exception e) {
            return 500;
        }
    }
}
