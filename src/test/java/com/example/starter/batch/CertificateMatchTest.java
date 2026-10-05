package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
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
 * 合格证声明发布/撤回/批量绑定/放行门禁测试：主流程、422 失败分支、批量回滚、
 * 有效区间左闭右开边界（可控时钟）、commandKey 幂等与并发裁决。真实 H2 库验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class CertificateMatchTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final String VALID_FROM = "2026-01-01T00:00:00Z";
    private static final String VALID_TO = "2027-01-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM certificate_binding");
        jdbc.update("DELETE FROM declaration_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM batch");
        clock.setInstant(T0);
    }

    // ---------- 声明发布 ----------

    @Test
    void publishHappy_historyAndDetailStable() throws Exception {
        publish("DECL-A", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        // 同物料前缀重叠但优先级不同 → 允许
        publish("DECL-A", 2, "MAT-1", List.of("LOT-9"), 1, VALID_FROM, VALID_TO, 422);
        publish("DECL-A", 2, "MAT-1", List.of("LOT-9"), 2, VALID_FROM, VALID_TO, 201);

        MvcResult history = mockMvc.perform(get("/api/declarations/DECL-A/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        assertEquals(2, node.path("versions").size());
        assertEquals(1, node.path("versions").get(0).path("version").asInt());
        assertEquals(2, node.path("versions").get(1).path("version").asInt());
        assertEquals("PUBLISHED", node.path("versions").get(0).path("status").asText());
        assertTrue(node.path("versions").get(0).path("withdrawnAt").isNull());
        assertEquals("2026-06-01T00:00:00Z", node.path("versions").get(0).path("publishedAt").asText());
        assertEquals(List.of("LOT-"), objectMapper.convertValue(
                node.path("versions").get(0).path("batchNoPrefixes"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        MvcResult detail = mockMvc.perform(get("/api/declarations/DECL-A/versions/2"))
                .andExpect(status().isOk()).andReturn();
        JsonNode v2 = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals("MAT-1", v2.path("materialCode").asText());
        assertEquals(2, v2.path("priority").asInt());
        mockMvc.perform(get("/api/declarations/DECL-A/versions/9"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/declarations/NO-SUCH/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishInvalidInterval_returns422_andFailureDoesNotOccupyKey() throws Exception {
        // validFrom == validTo → 422
        publish("DECL-T", 1, "MAT-1", List.of("L-"), 1, "2026-06-01T00:00:00Z",
                "2026-06-01T00:00:00Z", 422);
        // validFrom > validTo → 422
        publish("DECL-T", 1, "MAT-1", List.of("L-"), 1, "2027-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z", 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_declaration",
                Integer.class));
        // 失败不占键：同一 commandKey 修正参数后成功
        String body = publishBody("CK-T-1", "DECL-T", 1, "MAT-1", List.of("L-"), 1,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        String fixed = publishBody("CK-T-1", "DECL-T", 1, "MAT-1", List.of("L-"), 1,
                VALID_FROM, VALID_TO);
        mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated());
    }

    @Test
    void publishPrefixOverlap_sameMaterialSamePriority422_differentMaterialOk() throws Exception {
        publish("DECL-O1", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        // 互为前缀且优先级相同 → 422
        publish("DECL-O2", 1, "MAT-1", List.of("LOT-2026"), 1, VALID_FROM, VALID_TO, 422);
        publish("DECL-O3", 1, "MAT-1", List.of("LO"), 1, VALID_FROM, VALID_TO, 422);
        // 不重叠 → 201
        publish("DECL-O4", 1, "MAT-1", List.of("BATCH-"), 1, VALID_FROM, VALID_TO, 201);
        // 不同物料同前缀同优先级 → 201
        publish("DECL-O5", 1, "MAT-2", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        // 撤回后不再参与重叠校验
        withdraw("DECL-O1", 1, "CK-W-1", 200);
        publish("DECL-O6", 1, "MAT-1", List.of("LOT-2026"), 1, VALID_FROM, VALID_TO, 201);
    }

    @Test
    void publishCommandKey_replayAndConflict_andVersionNotRewritable() throws Exception {
        String body = publishBody("CK-IDEM-1", "DECL-I", 1, "MAT-1", List.of("L-"), 1,
                VALID_FROM, VALID_TO);
        MvcResult first = mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = 'DECL-I'",
                Integer.class));

        // 同 commandKey 改参（换操作人）→ 409
        String changed = publishBody("CK-IDEM-1", "DECL-I", 1, "MAT-1", List.of("L-"), 1,
                VALID_FROM, VALID_TO);
        mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s2")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
        // 同 key 同版本换 commandKey → 409（版本不可改写）
        String otherKey = publishBody("CK-IDEM-2", "DECL-I", 1, "MAT-1", List.of("L-"), 1,
                VALID_FROM, VALID_TO);
        mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "s1")
                        .contentType(MediaType.APPLICATION_JSON).content(otherKey))
                .andExpect(status().isConflict());
    }

    // ---------- 声明撤回 ----------

    @Test
    void withdrawFlow_onlyAffectsLaterOperations() throws Exception {
        publish("DECL-W", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        MvcResult result = withdraw("DECL-W", 1, "CK-W-1", 200);
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("WITHDRAWN", node.path("status").asText());
        assertEquals("2026-06-01T00:00:00Z", node.path("withdrawnAt").asText());
        // 已撤回再撤回 → 409；不存在 → 404
        withdraw("DECL-W", 1, "CK-W-2", 409);
        withdraw("DECL-W", 9, "CK-W-3", 404);
        // 撤回的声明不能绑定
        String batch = createBatch("BK-W-1", "MAT-1", "LOT-1", 201);
        bind("CK-B-W1", List.<String[]>of(new String[]{batch, "DECL-W", "1"}), 422);
        // 历史保留撤回事实
        MvcResult history = mockMvc.perform(get("/api/declarations/DECL-W/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("WITHDRAWN", objectMapper.readTree(history.getResponse().getContentAsString())
                .path("versions").get(0).path("status").asText());
    }

    // ---------- 批量绑定 ----------

    @Test
    void bindBulk_happy_andDiagnosis() throws Exception {
        publish("DECL-B", 1, "MAT-1", List.of("LOT-", "LT-"), 1, VALID_FROM, VALID_TO, 201);
        String b1 = createBatch("BK-B-1", "MAT-1", "LOT-100", 201);
        String b2 = createBatch("BK-B-2", "MAT-1", "LT-200", 201);

        MvcResult result = bind("CK-B-1",
                List.<String[]>of(new String[]{b2, "DECL-B", "1"}, new String[]{b1, "DECL-B", "1"}), 201);
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, node.path("boundCount").asInt());
        // 响应按 batchKey 升序（稳定排序）
        assertEquals(b1, node.path("bindings").get(0).path("batchKey").asText());
        assertEquals(b2, node.path("bindings").get(1).path("batchKey").asText());
        // 多前缀命中取最长
        assertEquals("LOT-", node.path("bindings").get(0).path("matchedPrefix").asText());
        assertEquals("LT-", node.path("bindings").get(1).path("matchedPrefix").asText());

        MvcResult detail = mockMvc.perform(get("/api/batches/" + b1 + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        JsonNode cert = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals("DECL-B", cert.path("binding").path("declarationKey").asText());
        assertEquals(1, cert.path("binding").path("version").asInt());
        assertEquals("MAT-1", cert.path("binding").path("materialCode").asText());
        assertTrue(cert.path("release").isNull());

        MvcResult diagnosis = mockMvc.perform(get("/api/batches/" + b1 + "/certificate/diagnosis"))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = objectMapper.readTree(diagnosis.getResponse().getContentAsString());
        assertTrue(diag.path("releasable").asBoolean());
        assertEquals(0, diag.path("reasons").size());
        assertEquals("2026-06-01T00:00:00Z", diag.path("checkedAt").asText());
    }

    @Test
    void bindDiagnosis_unboundBatchReportsReason() throws Exception {
        String batch = createBatch("BK-D-1", "MAT-1", "LOT-1", 201);
        MvcResult diagnosis = mockMvc.perform(get("/api/batches/" + batch + "/certificate/diagnosis"))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = objectMapper.readTree(diagnosis.getResponse().getContentAsString());
        assertFalse(diag.path("releasable").asBoolean());
        assertEquals(1, diag.path("reasons").size());
        assertTrue(diag.path("reasons").get(0).asText().contains("未绑定"));
        assertTrue(diag.path("binding").isNull());
        mockMvc.perform(get("/api/batches/NO-SUCH/certificate"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/certificate/diagnosis"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bindValidationErrors_return422or404or409() throws Exception {
        publish("DECL-V", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        // 物料不匹配 → 422
        String wrongMaterial = createBatch("BK-V-1", "MAT-OTHER", "LOT-1", 201);
        bind("CK-V-1", List.<String[]>of(new String[]{wrongMaterial, "DECL-V", "1"}), 422);
        // 批号前缀不匹配 → 422
        String wrongPrefix = createBatch("BK-V-2", "MAT-1", "XX-1", 201);
        bind("CK-V-2", List.<String[]>of(new String[]{wrongPrefix, "DECL-V", "1"}), 422);
        // 声明不存在 → 422
        String batch = createBatch("BK-V-3", "MAT-1", "LOT-3", 201);
        bind("CK-V-3", List.<String[]>of(new String[]{batch, "DECL-NONE", "1"}), 422);
        // 批次不存在 → 404
        bind("CK-V-4", List.<String[]>of(new String[]{"BK-NONE", "DECL-V", "1"}), 404);
        // 请求内批次重复 → 409
        bind("CK-V-5", List.<String[]>of(new String[]{batch, "DECL-V", "1"},
                new String[]{batch, "DECL-V", "1"}), 409);
        // 已绑定批次再绑定 → 409
        bind("CK-V-6", List.<String[]>of(new String[]{batch, "DECL-V", "1"}), 201);
        bind("CK-V-7", List.<String[]>of(new String[]{batch, "DECL-V", "1"}), 409);
        // 已拒绝批次不允许绑定 → 409
        String rejected = createBatch("BK-V-4", "MAT-1", "LOT-4", 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        bind("CK-V-8", List.<String[]>of(new String[]{rejected, "DECL-V", "1"}), 409);
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/batches/certificate-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V-9\",\"bindings\":[{\"batchKey\":\""
                                + batch + "\",\"declarationKey\":\"DECL-V\",\"version\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bindValidityInterval_checkedAtBindTime() throws Exception {
        // 已过期声明（相对固定时钟 2026-06-01）→ 422
        publish("DECL-EXP", 1, "MAT-1", List.of("LOT-"), 1,
                "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 201);
        String b1 = createBatch("BK-EXP-1", "MAT-1", "LOT-1", 201);
        bind("CK-EXP-1", List.<String[]>of(new String[]{b1, "DECL-EXP", "1"}), 422);
        // 尚未生效 → 422（不同优先级避免与 DECL-EXP 的前缀重叠校验）
        publish("DECL-FUT", 1, "MAT-1", List.of("LOT-"), 2,
                "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z", 201);
        bind("CK-EXP-2", List.<String[]>of(new String[]{b1, "DECL-FUT", "1"}), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class));
    }

    @Test
    void validityBoundary_leftClosedRightOpen_withControllableClock() throws Exception {
        publish("DECL-BND", 1, "MAT-1", List.of("LOT-"), 1,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 201);
        // 左闭：validFrom 当日 exactly 有效
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        String b1 = createBatch("BK-BND-1", "MAT-1", "LOT-1", 201);
        bind("CK-BND-1", List.<String[]>of(new String[]{b1, "DECL-BND", "1"}), 201);
        // 左闭之前一瞬 → 无效
        clock.setInstant(Instant.parse("2025-12-31T23:59:59Z"));
        String b2 = createBatch("BK-BND-2", "MAT-1", "LOT-2", 201);
        bind("CK-BND-2", List.<String[]>of(new String[]{b2, "DECL-BND", "1"}), 422);
        // 右开：validTo 当日 exactly 无效
        clock.setInstant(Instant.parse("2026-12-31T00:00:00Z"));
        String b3 = createBatch("BK-BND-3", "MAT-1", "LOT-3", 201);
        bind("CK-BND-3", List.<String[]>of(new String[]{b3, "DECL-BND", "1"}), 422);
        // 右开之前一瞬 → 有效
        clock.setInstant(Instant.parse("2026-12-30T23:59:59Z"));
        bind("CK-BND-4", List.<String[]>of(new String[]{b3, "DECL-BND", "1"}), 201);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class));
    }

    @Test
    void bulkBind_anyInvalidRollsBackAll() throws Exception {
        publish("DECL-RB", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        publish("DECL-RB2", 1, "MAT-OTHER", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        String b1 = createBatch("BK-RB-1", "MAT-1", "LOT-1", 201);
        String b2 = createBatch("BK-RB-2", "MAT-1", "LOT-2", 201);
        // 第二条声明物料与批次不匹配 → 整次 422 回滚
        bind("CK-RB-1", List.<String[]>of(new String[]{b1, "DECL-RB", "1"},
                new String[]{b2, "DECL-RB2", "1"}), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class), "任一失效声明整次回滚，查询不到半成品");
        // 失败不占键：修正后同 commandKey 成功
        bind("CK-RB-1", List.<String[]>of(new String[]{b1, "DECL-RB", "1"},
                new String[]{b2, "DECL-RB", "1"}), 201);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class));
    }

    @Test
    void bindCommandKey_replayAndConflict() throws Exception {
        publish("DECL-BI", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        String b1 = createBatch("BK-BI-1", "MAT-1", "LOT-1", 201);
        String b2 = createBatch("BK-BI-2", "MAT-1", "LOT-2", 201);
        // 集合顺序不影响指纹（规范化排序后重放一致）
        String body1 = bindBody("CK-BI-1",
                List.<String[]>of(new String[]{b1, "DECL-BI", "1"}, new String[]{b2, "DECL-BI", "1"}));
        String body2 = bindBody("CK-BI-1",
                List.<String[]>of(new String[]{b2, "DECL-BI", "1"}, new String[]{b1, "DECL-BI", "1"}));
        MvcResult first = mockMvc.perform(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_binding",
                Integer.class));
        // 同 commandKey 改参（换操作人）→ 409
        mockMvc.perform(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op-2")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isConflict());
    }

    // ---------- 放行门禁与快照 ----------

    @Test
    void releaseGate_requiresValidBinding_andSnapshotFrozen() throws Exception {
        publish("DECL-G", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        String batch = createBatch("BK-G-1", "MAT-1", "LOT-100", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-G-A1", 201);
        // 未绑定声明 → 第二批准 422，批次停留 RELEASE_REVIEW
        approve(batch, "ops-1", "OPERATIONS", "CK-G-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        bind("CK-G-B1", List.<String[]>of(new String[]{batch, "DECL-G", "1"}), 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-G-A3", 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 放行快照固化声明版本、匹配前缀与时点（固定时钟）
        MvcResult detail = mockMvc.perform(get("/api/batches/" + batch + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        JsonNode cert = objectMapper.readTree(detail.getResponse().getContentAsString());
        JsonNode release = cert.path("release");
        assertEquals("DECL-G", release.path("declarationKey").asText());
        assertEquals(1, release.path("version").asInt());
        assertEquals("LOT-", release.path("matchedPrefix").asText());
        assertEquals("2026-06-01T00:00:00Z", release.path("releasedAt").asText());

        // 声明撤回不改写历史快照，只影响后续放行
        withdraw("DECL-G", 1, "CK-G-W1", 200);
        MvcResult after = mockMvc.perform(get("/api/batches/" + batch + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(detail.getResponse().getContentAsString(),
                after.getResponse().getContentAsString());
        // 诊断只读：已撤回声明在诊断中可见，但不改变任何状态
        MvcResult diag = mockMvc.perform(get("/api/batches/" + batch + "/certificate/diagnosis"))
                .andExpect(status().isOk()).andReturn();
        JsonNode diagNode = objectMapper.readTree(diag.getResponse().getContentAsString());
        assertFalse(diagNode.path("releasable").asBoolean());
        assertTrue(diagNode.path("reasons").toString().contains("已撤回"));
        assertEquals("RELEASED", currentStatus(batch));
    }

    @Test
    void releaseGate_withdrawnOrExpiredDeclaration_blocksRelease() throws Exception {
        publish("DECL-X", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        publish("DECL-Y", 1, "MAT-1", List.of("LOT-"), 2,
                "2026-01-01T00:00:00Z", "2026-06-01T00:00:01Z", 201);
        String b1 = createBatch("BK-X-1", "MAT-1", "LOT-1", 201);
        String b2 = createBatch("BK-X-2", "MAT-1", "LOT-2", 201);
        bind("CK-X-B", List.<String[]>of(new String[]{b1, "DECL-X", "1"},
                new String[]{b2, "DECL-Y", "1"}), 201);
        for (String batch : List.of(b1, b2)) {
            submitTest(batch, "t1", "PASS", "insp", 201);
            approve(batch, "qa-1", "QUALITY", "CK-X-AQ-" + batch, 201);
        }
        // 推进时钟：DECL-Y 在放行时点已过期（validTo 2026-06-01T00:00:01Z）
        clock.setInstant(Instant.parse("2026-06-01T00:00:02Z"));
        // 绑定时有效、放行前撤回 → 422
        withdraw("DECL-X", 1, "CK-X-W", 200);
        approve(b1, "ops-1", "OPERATIONS", "CK-X-AO-1", 422);
        // 绑定时有效、放行时已过期（validTo 2026-06-01T00:00:01Z 早于当前时点）→ 422
        approve(b2, "ops-1", "OPERATIONS", "CK-X-AO-2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b1));
        assertEquals("RELEASE_REVIEW", currentStatus(b2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot",
                Integer.class));
    }

    @Test
    void splitChildren_inheritBindingReference() throws Exception {
        publish("DECL-SP", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        String parent = createBatch("BK-SP-P", "MAT-1", "LOT-1", 201);
        bind("CK-SP-B", List.<String[]>of(new String[]{parent, "DECL-SP", "1"}), 201);
        submitTest(parent, "t1", "PASS", "insp", 201);
        approve(parent, "qa-1", "QUALITY", "CK-SP-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SP-A2", 201);
        assertEquals("RELEASED", currentStatus(parent));

        String c1 = "BK-SP-C1-" + unique();
        String c2 = "BK-SP-C2-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SP-S\",\"children\":["
                                + "{\"batchKey\":\"" + c1 + "\",\"batchNo\":\"LOT-C1\"},"
                                + "{\"batchKey\":\"" + c2 + "\",\"batchNo\":\"LOT-C2\"}]}"))
                .andExpect(status().isCreated());

        // 子批继承父批绑定引用（同一声明版本），操作人标记为 SYSTEM
        for (String child : List.of(c1, c2)) {
            MvcResult detail = mockMvc.perform(get("/api/batches/" + child + "/certificate"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode binding = objectMapper.readTree(detail.getResponse().getContentAsString())
                    .path("binding");
            assertEquals("DECL-SP", binding.path("declarationKey").asText());
            assertEquals(1, binding.path("version").asInt());
            assertEquals("SYSTEM", binding.path("actorId").asText());
        }
        // 继承绑定的子批可直接走完放行（门禁读取继承的声明）
        submitTest(c1, "t1", "PASS", "insp-2", 201);
        approve(c1, "qa-2", "QUALITY", "CK-SP-CA1", 201);
        approve(c1, "ops-2", "OPERATIONS", "CK-SP-CA2", 201);
        assertEquals("RELEASED", currentStatus(c1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, c1));
    }

    // ---------- 并发 ----------

    @Test
    void concurrentWithdrawAndFinalApproval_commitOrderDecides() throws Exception {
        publish("DECL-CW", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        String batch = createBatch("BK-CW-1", "MAT-1", "LOT-1", 201);
        bind("CK-CW-B", List.<String[]>of(new String[]{batch, "DECL-CW", "1"}), 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa-1", "QUALITY", "CK-CW-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-A2\"}")),
                () -> callStatus(post("/api/declarations/DECL-CW/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-W\"}"))
        );
        int approval = results.get(0).get(30, TimeUnit.SECONDS);
        int withdraw = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, withdraw, "撤回始终可提交");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class, batch);
        if (approval == 201) {
            // 放行先提交：批次 RELEASED 且快照固化，撤回只影响后续
            assertEquals("RELEASED", currentStatus(batch));
            assertEquals(1, snapshots);
        } else {
            // 撤回先提交：放行读取到已撤回声明 → 422，无快照
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batch));
            assertEquals(0, snapshots);
        }
    }

    @Test
    void concurrentBindSameBatch_onlyOneWins() throws Exception {
        publish("DECL-CB1", 1, "MAT-1", List.of("LOT-"), 1, VALID_FROM, VALID_TO, 201);
        publish("DECL-CB2", 1, "MAT-1", List.of("LOT-"), 2, VALID_FROM, VALID_TO, 201);
        String batch = createBatch("BK-CB-1", "MAT-1", "LOT-1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-1",
                                List.<String[]>of(new String[]{batch, "DECL-CB1", "1"})))),
                () -> callStatus(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-2",
                                List.<String[]>of(new String[]{batch, "DECL-CB2", "1"}))))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "同一批次并发绑定仅一个成功: " + first + "/" + second);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batch));
    }

    @Test
    void concurrentPublishSameVersion_onlyOneWins() throws Exception {
        String body = publishBody("CK-CP-1", "DECL-CP", 1, "MAT-1", List.of("LOT-"), 1,
                VALID_FROM, VALID_TO);
        // 不同前缀避免重叠校验干扰：并发同 key 同版本只能由唯一约束裁决出一个 201
        String body2 = publishBody("CK-CP-2", "DECL-CP", 1, "MAT-1", List.of("LT-"), 1,
                VALID_FROM, VALID_TO);
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/declarations").header("X-Actor-Id", "s1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/declarations").header("X-Actor-Id", "s2")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 201 && second == 409) || (first == 409 && second == 201),
                "同 key 同版本并发发布仅一个成功: " + first + "/" + second);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = 'DECL-CP'",
                Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建批次（必做检验项固定为 t1），batchKey 加唯一后缀，返回实际 batchKey。
     */
    private String createBatch(String batchKeyPrefix, String productCode, String batchNo,
                               int expected) throws Exception {
        String batchKey = batchKeyPrefix + "-" + unique();
        String body = "{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\"" + batchKey
                + "\",\"productCode\":\"" + productCode + "\",\"batchNo\":\"" + batchNo
                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}";
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
        return batchKey;
    }

    private String publishBody(String commandKey, String declarationKey, int version,
                               String materialCode, List<String> prefixes, int priority,
                               String validFrom, String validTo) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"declarationKey\":\"").append(declarationKey)
                .append("\",\"version\":").append(version)
                .append(",\"materialCode\":\"").append(materialCode)
                .append("\",\"batchNoPrefixes\":[");
        for (int i = 0; i < prefixes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(prefixes.get(i)).append('"');
        }
        return sb.append("],\"priority\":").append(priority)
                .append(",\"validFrom\":\"").append(validFrom)
                .append("\",\"validTo\":\"").append(validTo).append("\"}").toString();
    }

    private MvcResult publish(String declarationKey, int version, String materialCode,
                              List<String> prefixes, int priority, String validFrom,
                              String validTo, int expected) throws Exception {
        return mockMvc.perform(post("/api/declarations").header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-P-" + unique(), declarationKey, version,
                                materialCode, prefixes, priority, validFrom, validTo)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult withdraw(String declarationKey, int version, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/declarations/" + declarationKey + "/versions/" + version
                        + "/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String bindBody(String commandKey, List<String[]> specs) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"bindings\":[");
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(specs.get(i)[0])
                    .append("\",\"declarationKey\":\"").append(specs.get(i)[1])
                    .append("\",\"version\":").append(specs.get(i)[2]).append('}');
        }
        return sb.append("]}").toString();
    }

    private MvcResult bind(String commandKey, List<String[]> specs, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody(commandKey, specs)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"" + item + "\",\"result\":\""
                                + result + "\",\"inspector\":\"" + inspector + "\"}"))
                .andExpect(status().is(expected));
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
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


