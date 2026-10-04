package com.example.starter.batch;

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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合格证声明发布、撤回、查询与诊断测试：主流程、422 校验分支、
 * commandKey 幂等与指纹防碰撞、稳定排序与 null 语义。
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
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 发布主流程与查询 ----------

    @Test
    void publishHappy_listAndHistoryStableSorted_nullSemantics() throws Exception {
        String keyB = "DECL-B-" + unique();
        String keyA = "DECL-A-" + unique();
        publish(keyB, 2, "MAT-1", "LOT-", 5, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish(keyA, 1, "MAT-1", "LOT-", 6, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish(keyB, 1, "MAT-1", "LOT-", 7, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);

        // 列表按物料、声明键、版本稳定排序
        MvcResult list = mockMvc.perform(get("/api/certificates/declarations")
                        .param("materialCode", "MAT-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(list.getResponse().getContentAsString());
        assertEquals(3, rows.size());
        assertEquals(keyA, rows.get(0).path("declarationKey").asText());
        assertEquals(keyB, rows.get(1).path("declarationKey").asText());
        assertEquals(1, rows.get(1).path("version").asInt());
        assertEquals(keyB, rows.get(2).path("declarationKey").asText());
        assertEquals(2, rows.get(2).path("version").asInt());

        // 未撤回时 withdrawnBy/withdrawnAt 为 null；时间精度为秒级 UTC
        JsonNode first = rows.get(0);
        assertTrue(first.path("withdrawnBy").isNull());
        assertTrue(first.path("withdrawnAt").isNull());
        assertEquals("ACTIVE", first.path("status").asText());
        assertEquals("2026-01-01T00:00:00Z", first.path("validFrom").asText());

        // 版本历史按版本号稳定排序
        MvcResult history = mockMvc.perform(get("/api/certificates/declarations/" + keyB + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode historyRows = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(2, historyRows.size());
        assertEquals(1, historyRows.get(0).path("version").asInt());
        assertEquals(2, historyRows.get(1).path("version").asInt());

        // 不存在的声明键历史 → 404
        mockMvc.perform(get("/api/certificates/declarations/NO-SUCH/history"))
                .andExpect(status().isNotFound());
    }

    // ---------- 发布校验失败分支 ----------

    @Test
    void publishInvalidValidityWindow_returns422() throws Exception {
        // 起点等于终点
        JsonNode eq = publish("DECL-EQ-" + unique(), 1, "MAT-1", "L", 1,
                "2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z", 422);
        assertEquals("INVALID_VALIDITY_WINDOW", eq.path("code").asText());
        // 起点晚于终点
        JsonNode gt = publish("DECL-GT-" + unique(), 1, "MAT-1", "L", 1,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 422);
        assertEquals("INVALID_VALIDITY_WINDOW", gt.path("code").asText());
        assertEquals(0, countDeclarations());
    }

    @Test
    void publishPrefixOverlapSamePriority_returns422_differentPriorityAllowed() throws Exception {
        publish("DECL-WIDE-" + unique(), 1, "MAT-1", "LOT-", 3,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 前缀被包含且优先级相同 → 422
        JsonNode conflict = publish("DECL-NARROW-" + unique(), 1, "MAT-1", "LOT-9", 3,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 422);
        assertEquals("PREFIX_PRIORITY_CONFLICT", conflict.path("code").asText());
        // 前缀重叠但优先级不同 → 允许
        publish("DECL-NARROW2-" + unique(), 1, "MAT-1", "LOT-9", 9,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 前缀不重叠、优先级相同 → 允许
        publish("DECL-OTHER-" + unique(), 1, "MAT-1", "OTH-", 3,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        // 不同物料互不影响
        publish("DECL-MAT2-" + unique(), 1, "MAT-2", "LOT-", 3,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        assertEquals(4, countDeclarations());
    }

    @Test
    void publishOverlapWithWithdrawnDeclaration_allowed() throws Exception {
        String key = "DECL-WD-" + unique();
        publish(key, 1, "MAT-1", "LOT-", 2,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        withdraw(key, 1, 200);
        // 已撤回声明不参与重叠冲突
        publish("DECL-NEW-" + unique(), 1, "MAT-1", "LOT-9", 2,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
    }

    @Test
    void publishDuplicateKeyVersion_returns409() throws Exception {
        String key = "DECL-DUP-" + unique();
        publish(key, 1, "MAT-1", "L", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish(key, 1, "MAT-1", "M", 2, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 409);
    }
    // ---------- 撤回 ----------

    @Test
    void withdrawHappy_thenDuplicateWithdraw409_unknownWithdraw404() throws Exception {
        String key = "DECL-W-" + unique();
        publish(key, 1, "MAT-1", "L", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);

        MvcResult result = withdraw(key, 1, 200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("WITHDRAWN", body.path("status").asText());
        assertEquals("supplier-1", body.path("withdrawnBy").asText());
        assertFalse(body.path("withdrawnAt").isNull());
        // 发布时间保留不改写
        assertFalse(body.path("publishedAt").isNull());

        // 历史记录保留撤回事实
        MvcResult history = mockMvc.perform(get("/api/certificates/declarations/" + key + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals("WITHDRAWN", rows.get(0).path("status").asText());
        assertEquals("supplier-1", rows.get(0).path("withdrawnBy").asText());

        // 重复撤回 → 409；未知版本 → 404
        withdraw(key, 1, 409);
        mockMvc.perform(post("/api/certificates/declarations/" + key + "/versions/9/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-W-9-" + unique() + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void publishCommandKey_sameParamsReplay_changedParams409_failureDoesNotOccupyKey()
            throws Exception {
        String key = "DECL-IDEM-" + unique();
        String body = publishBody("CK-PUB-1", key, 1, "MAT-1", "LOT-", 4,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        MvcResult first = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countDeclarations());

        // 同 commandKey 改参（优先级不同）→ 409
        String changed = publishBody("CK-PUB-1", key, 1, "MAT-1", "LOT-", 8,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：先用 CK-PUB-F 发一个区间非法的请求（422），修正参数后同键成功
        String bad = publishBody("CK-PUB-F", "DECL-F-" + unique(), 1, "MAT-1", "L", 1,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity());
        String good = publishBody("CK-PUB-F", "DECL-F2-" + unique(), 1, "MAT-1", "L", 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isCreated());
    }

    @Test
    void withdrawCommandKey_replayAndConflict() throws Exception {
        String key = "DECL-WIDEM-" + unique();
        publish(key, 1, "MAT-1", "L", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        String body = "{\"commandKey\":\"CK-WD-1\"}";
        MvcResult first = mockMvc.perform(post("/api/certificates/declarations/" + key
                        + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/declarations/" + key
                        + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同 commandKey 换操作人 → 409（指纹含操作者）
        mockMvc.perform(post("/api/certificates/declarations/" + key + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void fingerprint_lengthPrefixEncoding_noSeparatorCollision() throws Exception {
        // 朴素拼接下 ("AB","C") 与 ("A","BC") 会碰撞；长度前缀编码必须区分
        String key = "DECL-FP-" + unique();
        String first = publishBody("CK-FP-1", key, 1, "AB", "C", 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated());
        String colliding = publishBody("CK-FP-1", key, 1, "A", "BC", 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(colliding))
                .andExpect(status().isConflict());
    }
    // ---------- 诊断查询 ----------

    @Test
    void diagnostics_picksHighestPriorityEligible_withReasons() throws Exception {
        String low = "DECL-DG-LOW-" + unique();
        String high = "DECL-DG-HIGH-" + unique();
        String withdrawn = "DECL-DG-WD-" + unique();
        publish(low, 1, "MAT-9", "LOT-", 1,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish(high, 1, "MAT-9", "LOT-5", 9,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        publish(withdrawn, 1, "MAT-9", "LOT-55", 7,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", 201);
        withdraw(withdrawn, 1, 200);

        MvcResult result = mockMvc.perform(get("/api/certificates/diagnostics")
                        .param("materialCode", "MAT-9")
                        .param("batchNo", "LOT-55-001")
                        .param("at", "2026-06-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(high, body.path("matchedDeclarationKey").asText());
        assertEquals(1, body.path("matchedVersion").asInt());
        assertFalse(body.path("ambiguous").asBoolean());
        assertEquals("2026-06-01T00:00:00Z", body.path("at").asText());

        // 候选按优先级降序稳定排序，各自给出判定原因
        JsonNode candidates = body.path("candidates");
        assertEquals(3, candidates.size());
        assertEquals(high, candidates.get(0).path("declarationKey").asText());
        assertTrue(candidates.get(0).path("eligible").asBoolean());
        assertEquals(withdrawn, candidates.get(1).path("declarationKey").asText());
        assertFalse(candidates.get(1).path("eligible").asBoolean());
        assertEquals("声明已撤回", candidates.get(1).path("reason").asText());
        assertEquals(low, candidates.get(2).path("declarationKey").asText());
        assertTrue(candidates.get(2).path("eligible").asBoolean());
    }

    @Test
    void diagnostics_outsideValidityWindow_andPrefixMismatch() throws Exception {
        String key = "DECL-DG-WIN-" + unique();
        publish(key, 1, "MAT-8", "LOT-", 1,
                "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", 201);

        // 右开区间：at == validTo 已失效
        MvcResult atEnd = mockMvc.perform(get("/api/certificates/diagnostics")
                        .param("materialCode", "MAT-8")
                        .param("batchNo", "LOT-1")
                        .param("at", "2026-06-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        JsonNode endBody = objectMapper.readTree(atEnd.getResponse().getContentAsString());
        assertTrue(endBody.path("matchedDeclarationKey").isNull());
        assertFalse(endBody.path("candidates").get(0).path("withinValidity").asBoolean());

        // 左闭区间：at == validFrom 有效
        MvcResult atStart = mockMvc.perform(get("/api/certificates/diagnostics")
                        .param("materialCode", "MAT-8")
                        .param("batchNo", "LOT-1")
                        .param("at", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(key, objectMapper.readTree(atStart.getResponse().getContentAsString())
                .path("matchedDeclarationKey").asText());

        // 批号前缀不匹配
        MvcResult wrongPrefix = mockMvc.perform(get("/api/certificates/diagnostics")
                        .param("materialCode", "MAT-8")
                        .param("batchNo", "OTH-1")
                        .param("at", "2026-03-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        JsonNode prefixBody = objectMapper.readTree(wrongPrefix.getResponse().getContentAsString());
        assertTrue(prefixBody.path("matchedDeclarationKey").isNull());
        assertFalse(prefixBody.path("candidates").get(0).path("prefixMatched").asBoolean());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int countDeclarations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM certificate_declaration", Integer.class);
    }

    private String publishBody(String commandKey, String declarationKey, int version,
                               String materialCode, String prefix, int priority,
                               String validFrom, String validTo) {
        return "{\"commandKey\":\"" + commandKey + "\",\"declarationKey\":\"" + declarationKey
                + "\",\"version\":" + version + ",\"materialCode\":\"" + materialCode
                + "\",\"batchNoPrefix\":\"" + prefix + "\",\"priority\":" + priority
                + ",\"validFrom\":\"" + validFrom + "\",\"validTo\":\"" + validTo + "\"}";
    }

    private JsonNode publish(String declarationKey, int version, String materialCode,
                             String prefix, int priority, String validFrom, String validTo,
                             int expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-P-" + unique(), declarationKey, version,
                                materialCode, prefix, priority, validFrom, validTo)))
                .andExpect(status().is(expected)).andReturn();
        if (expected >= 400) {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        }
        return null;
    }

    private MvcResult withdraw(String declarationKey, int version, int expected) throws Exception {
        return mockMvc.perform(post("/api/certificates/declarations/" + declarationKey
                        + "/versions/" + version + "/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-W-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }
}