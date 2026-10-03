package com.example.starter.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合格证声明发布、撤回与诊断测试：主流程、区间/前缀重叠 422、版本冲突 409、
 * commandKey 幂等、撤回语义、诊断排序与并发发布裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateDeclarationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM certificate_declaration_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 发布主流程与明细查询 ----------

    @Test
    void publishHappy_versionsListedInOrder_withdrawnFieldsNull() throws Exception {
        publish("DECL-A", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish("DECL-A", 2, "PROD-1", List.of("LOT", "LOT-9"), 2,
                "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z", 201);

        MvcResult detail = mockMvc.perform(get("/api/declarations/DECL-A"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals("DECL-A", node.path("declarationKey").asText());
        assertEquals(2, node.path("versions").size());
        JsonNode v1 = node.path("versions").get(0);
        assertEquals(1, v1.path("version").asInt());
        assertEquals("ACTIVE", v1.path("status").asText());
        assertEquals("SUP-1", v1.path("supplierId").asText());
        assertEquals("PROD-1", v1.path("materialCode").asText());
        assertEquals("2026-01-01T00:00:00Z", v1.path("validFrom").asText());
        assertEquals("2027-01-01T00:00:00Z", v1.path("validTo").asText());
        assertTrue(v1.path("withdrawnAt").isNull());
        assertTrue(v1.path("withdrawnBy").isNull());
        JsonNode v2 = node.path("versions").get(1);
        assertEquals(2, v2.path("version").asInt());
        assertEquals(2, v2.path("prefixes").size());
        assertEquals("LOT-9", v2.path("prefixes").get(1).asText());

        mockMvc.perform(get("/api/declarations/NO-SUCH-DECL"))
                .andExpect(status().isNotFound());
    }

    // ---------- 有效区间非法 422 ----------

    @Test
    void invalidInterval_returns422_andNothingStored() throws Exception {
        // 起点等于终点
        publish("DECL-BAD-1", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 422);
        // 起点晚于终点
        publish("DECL-BAD-2", 1, "PROD-1", List.of("LOT"), 1,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration", Integer.class));
        mockMvc.perform(get("/api/declarations/DECL-BAD-1"))
                .andExpect(status().isNotFound());
    }

    // ---------- 前缀重叠 ----------

    @Test
    void prefixOverlap_samePriority422_differentPriorityOrMaterialOk_withdrawnIgnored()
            throws Exception {
        publish("DECL-O-A", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 同物料同优先级，前缀互为前缀（LOT 与 LOT-1）→ 422
        MvcResult rejected = publish("DECL-O-B", 1, "PROD-1", List.of("LOT-1"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 422);
        assertEquals("PREFIX_OVERLAP", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        // 不同优先级 → 允许
        publish("DECL-O-C", 1, "PROD-1", List.of("LOT-1"), 2,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 不同物料 → 允许
        publish("DECL-O-D", 1, "PROD-2", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 同优先级但前缀不重叠 → 允许
        publish("DECL-O-E", 1, "PROD-1", List.of("RET"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 撤回后不再参与重叠校验
        withdraw("DECL-O-A", 1, "CK-WD-O", 200);
        publish("DECL-O-F", 1, "PROD-1", List.of("LOT-8"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
    }

    // ---------- 版本唯一与内容冲突 ----------

    @Test
    void sameVersion_republishSameContentReturns200_differentContent409() throws Exception {
        publish("DECL-V", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 新 commandKey、同业务键同版本同内容 → 200 返回既有版本
        MvcResult again = mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pubBody("CK-PUB-NEW-" + unique(), "DECL-V", 1, "PROD-1",
                                List.of("LOT"), 1, "2026-01-01T00:00:00Z",
                                "2027-01-01T00:00:00Z")))
                .andExpect(status().isOk()).andReturn();
        assertEquals("DECL-V", objectMapper
                .readTree(again.getResponse().getContentAsString())
                .path("declarationKey").asText());
        // 同版本不同内容 → 409
        publish("DECL-V", 1, "PROD-1", List.of("LOT", "LOT-2"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 409);
        // 同业务键新版本号 → 允许
        publish("DECL-V", 2, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = 'DECL-V'",
                Integer.class));
    }

    // ---------- commandKey 幂等 ----------

    @Test
    void commandKey_replayReturnsFirstSnapshot_changedParams409_failureDoesNotOccupyKey()
            throws Exception {
        String body = pubBody("CK-IDEM-P", "DECL-I", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        MvcResult first = mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration", Integer.class));

        // 同 commandKey 改参 → 409
        String changed = pubBody("CK-IDEM-P", "DECL-I", 1, "PROD-1", List.of("LOT-1"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：非法区间 422 后同键修正参数成功（不同优先级避免与 DECL-I 前缀重叠）
        String bad = pubBody("CK-IDEM-F", "DECL-F", 1, "PROD-1", List.of("LOT"), 2,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity());
        String fixed = pubBody("CK-IDEM-F", "DECL-F", 1, "PROD-1", List.of("LOT"), 2,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated());
    }

    // ---------- 撤回 ----------

    @Test
    void withdraw_marksVersion_replayAndConflictAnd404() throws Exception {
        publish("DECL-W", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);

        MvcResult first = withdraw("DECL-W", 1, "CK-WD-1", 200);
        JsonNode body = objectMapper.readTree(first.getResponse().getContentAsString());
        assertEquals("WITHDRAWN", body.path("status").asText());
        assertEquals("sup-1", body.path("withdrawnBy").asText());
        assertFalse(body.path("withdrawnAt").isNull());

        // 同命令键同体重放 → 首次快照
        MvcResult replay = withdraw("DECL-W", 1, "CK-WD-1", 200);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 新命令键重复撤回 → 409
        withdraw("DECL-W", 1, "CK-WD-2", 409);
        // 不存在的版本 → 404
        withdraw("DECL-W", 9, "CK-WD-3", 404);
        withdraw("NO-SUCH", 1, "CK-WD-4", 404);

        // 明细保留撤回时点与操作人；历史版本不删除
        MvcResult detail = mockMvc.perform(get("/api/declarations/DECL-W"))
                .andExpect(status().isOk()).andReturn();
        JsonNode v1 = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("versions").get(0);
        assertEquals("WITHDRAWN", v1.path("status").asText());
        assertEquals("sup-1", v1.path("withdrawnBy").asText());
    }

    // ---------- 诊断查询 ----------

    @Test
    void diagnostics_evaluatesAllDeclarations_winnerByPriority_stableOrdering() throws Exception {
        // 匹配且优先级高
        publish("DECL-D-HI", 1, "PROD-1", List.of("LOT"), 5,
                "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 201);
        // 匹配但优先级低
        publish("DECL-D-LO", 1, "PROD-1", List.of("LOT-1"), 1,
                "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 201);
        // 物料不匹配
        publish("DECL-D-MAT", 1, "PROD-2", List.of("LOT"), 1,
                "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 201);
        // 已撤回
        publish("DECL-D-WD", 1, "PROD-1", List.of("LOT"), 9,
                "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 201);
        withdraw("DECL-D-WD", 1, "CK-WD-D", 200);
        // 已过期
        publish("DECL-D-EXP", 1, "PROD-1", List.of("LOT"), 2,
                "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", 201);
        // 尚未生效
        publish("DECL-D-FUT", 1, "PROD-1", List.of("LOT"), 3,
                "2099-01-01T00:00:00Z", "2099-02-01T00:00:00Z", 201);
        // 前缀不匹配
        publish("DECL-D-PFX", 1, "PROD-1", List.of("RET"), 4,
                "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z", 201);

        MvcResult result = mockMvc.perform(get("/api/declarations/diagnostics")
                        .param("materialCode", "PROD-1")
                        .param("batchNo", "LOT-123")
                        .param("at", "2026-06-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("PROD-1", node.path("materialCode").asText());
        assertEquals("LOT-123", node.path("batchNo").asText());
        assertEquals("2026-06-01T00:00:00Z", node.path("evaluatedAt").asText());
        assertEquals(7, node.path("totalDeclarations").asInt());
        assertEquals(2, node.path("matchedCount").asInt());
        assertFalse(node.path("ambiguous").asBoolean());
        // 胜者为优先级最高的匹配版本
        assertEquals("DECL-D-HI", node.path("winner").path("declarationKey").asText());
        assertEquals("LOT", node.path("winner").path("matchedPrefix").asText());

        JsonNode candidates = node.path("candidates");
        assertEquals(7, candidates.size());
        // 匹配项在前，按优先级降序
        assertEquals("DECL-D-HI", candidates.get(0).path("declarationKey").asText());
        assertTrue(candidates.get(0).path("matched").asBoolean());
        assertEquals(0, candidates.get(0).path("reasons").size());
        assertEquals("DECL-D-LO", candidates.get(1).path("declarationKey").asText());
        assertEquals("LOT-1", candidates.get(1).path("matchedPrefix").asText());
        // 未匹配项携带可区分原因码
        assertReasons(candidates, "DECL-D-MAT", "MATERIAL_MISMATCH");
        assertReasons(candidates, "DECL-D-WD", "WITHDRAWN");
        assertReasons(candidates, "DECL-D-EXP", "EXPIRED");
        assertReasons(candidates, "DECL-D-FUT", "NOT_YET_VALID");
        assertReasons(candidates, "DECL-D-PFX", "PREFIX_MISMATCH");
    }

    // ---------- 并发发布 ----------

    @Test
    void concurrentPublish_overlapSamePriority_exactlyOneSucceeds() throws Exception {
        String bodyA = pubBody("CK-CP-A", "DECL-CP-A", 1, "PROD-1", List.of("LOT"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        String bodyB = pubBody("CK-CP-B", "DECL-CP-B", 1, "PROD-1", List.of("LOT-1"), 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/declarations").header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/declarations").header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 按提交顺序裁决：恰一个 201，另一个 422 前缀重叠
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "并发重叠发布恰有一个成功: " + first + ", " + second);
        assertTrue(first == 422 || second == 422);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration", Integer.class));
    }

    // ---------- helpers ----------

    private void assertReasons(JsonNode candidates, String declarationKey, String reason) {
        for (JsonNode candidate : candidates) {
            if (candidate.path("declarationKey").asText().equals(declarationKey)) {
                assertFalse(candidate.path("matched").asBoolean(), declarationKey + " 不应匹配");
                assertTrue(candidate.path("matchedPrefix").isNull());
                List<String> reasons = new ArrayList<>();
                candidate.path("reasons").forEach(r -> reasons.add(r.asText()));
                assertTrue(reasons.contains(reason),
                        declarationKey + " 应包含原因 " + reason + ": " + reasons);
                return;
            }
        }
        fail("诊断候选中未找到 " + declarationKey);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String pubBody(String commandKey, String declarationKey, int version,
                           String materialCode, List<String> prefixes, int priority,
                           String validFrom, String validTo) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"declarationKey\":\"").append(declarationKey)
                .append("\",\"version\":").append(version)
                .append(",\"supplierId\":\"SUP-1\",\"materialCode\":\"").append(materialCode)
                .append("\",\"prefixes\":[");
        for (int i = 0; i < prefixes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(prefixes.get(i)).append('\"');
        }
        return sb.append("],\"priority\":").append(priority)
                .append(",\"validFrom\":\"").append(validFrom)
                .append("\",\"validTo\":\"").append(validTo).append("\"}").toString();
    }

    private MvcResult publish(String declarationKey, int version, String materialCode,
                              List<String> prefixes, int priority, String validFrom,
                              String validTo, int expected) throws Exception {
        return mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pubBody("CK-PUB-" + unique(), declarationKey, version,
                                materialCode, prefixes, priority, validFrom, validTo)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult withdraw(String declarationKey, int version, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/declarations/" + declarationKey + "/versions/"
                        + version + "/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}