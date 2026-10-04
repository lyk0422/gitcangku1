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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次合格证绑定与放行门禁测试：绑定校验 422 分支、批量绑定整次回滚、
 * 放行快照固化、撤回只影响后续放行、绑定幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateBindingReleaseTest {

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

    // ---------- 绑定主流程与失败分支 ----------

    @Test
    void bindHappy_certificateEndpointShowsBinding_snapshotNull() throws Exception {
        String batchKey = "BK-BIND-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        String decl = publishDefault();

        MvcResult result = bind(batchKey, decl, 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(batchKey, body.path("batchKey").asText());
        assertEquals(decl, body.path("declarationKey").asText());
        assertEquals("LOT-", body.path("matchedPrefix").asText());
        assertEquals("binder-1", body.path("boundBy").asText());

        JsonNode cert = certificate(batchKey);
        assertEquals(decl, cert.path("binding").path("declarationKey").asText());
        assertEquals(1, cert.path("binding").path("declarationVersion").asInt());
        assertTrue(cert.path("releaseSnapshot").isNull(), "未放行时快照为 null");

        // 未绑定批次的明细：binding 为 null
        String other = "BK-NOBIND-" + unique();
        createBatch(other, "PROD-1", "LOT-2", 201);
        JsonNode none = certificate(other);
        assertTrue(none.path("binding").isNull());
        assertTrue(none.path("releaseSnapshot").isNull());
        // 不存在批次 → 404
        mockMvc.perform(get("/api/batches/NO-SUCH/certificate")).andExpect(status().isNotFound());
    }

    @Test
    void bindValidationFailures_return422WithDistinctCodes() throws Exception {
        String decl = publishDefault();
        // 物料不匹配
        String wrongMaterial = "BK-WM-" + unique();
        createBatch(wrongMaterial, "PROD-2", "LOT-1", 201);
        JsonNode m = objectMapper.readTree(bind(wrongMaterial, decl, 422)
                .getResponse().getContentAsString());
        assertEquals("MATERIAL_MISMATCH", m.path("code").asText());
        // 批号前缀不匹配
        String wrongPrefix = "BK-WP-" + unique();
        createBatch(wrongPrefix, "PROD-1", "OTH-1", 201);
        JsonNode p = objectMapper.readTree(bind(wrongPrefix, decl, 422)
                .getResponse().getContentAsString());
        assertEquals("BATCH_NO_PREFIX_MISMATCH", p.path("code").asText());
        // 声明已撤回
        String withdrawnDecl = publishDefault();
        withdraw(withdrawnDecl, 200);
        String batch2 = "BK-WD-" + unique();
        createBatch(batch2, "PROD-1", "LOT-2", 201);
        JsonNode w = objectMapper.readTree(bind(batch2, withdrawnDecl, 422)
                .getResponse().getContentAsString());
        assertEquals("DECLARATION_WITHDRAWN", w.path("code").asText());
        // 声明不在有效区间（窗口已过期）
        String expiredDecl = "DECL-EXP-" + unique();
        publish(expiredDecl, "PROD-1", "LOT-", 8, "2020-01-01T00:00:00Z",
                "2021-01-01T00:00:00Z", 201);
        String batch3 = "BK-EXP-" + unique();
        createBatch(batch3, "PROD-1", "LOT-3", 201);
        JsonNode e = objectMapper.readTree(bind(batch3, expiredDecl, 422)
                .getResponse().getContentAsString());
        assertEquals("DECLARATION_NOT_EFFECTIVE", e.path("code").asText());
        // 全部失败均不产生绑定
        assertEquals(0, countRows("batch_certificate_binding"));
    }

    @Test
    void bindUnknownTargets_return404_andTerminalState409() throws Exception {
        String decl = publishDefault();
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-B-" + unique(), "NO-SUCH-BATCH", decl)))
                .andExpect(status().isNotFound());
        String batchKey = "BK-TERM-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-B-" + unique(), batchKey, "NO-SUCH-DECL")))
                .andExpect(status().isNotFound());
        // 已放行批次不可再绑定
        releaseBatch(batchKey, decl);
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-B-" + unique(), batchKey, decl)))
                .andExpect(status().isConflict());
    }

    // ---------- 批量绑定 ----------

    @Test
    void batchBindHappy_requestedEqualsBound() throws Exception {
        String decl = publishDefault();
        String b1 = "BK-BB1-" + unique();
        String b2 = "BK-BB2-" + unique();
        createBatch(b1, "PROD-1", "LOT-1", 201);
        createBatch(b2, "PROD-1", "LOT-2", 201);

        MvcResult result = mockMvc.perform(post("/api/certificates/bindings/batch")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BB-" + unique() + "\",\"bindings\":["
                                + bindItem(b1, decl) + "," + bindItem(b2, decl) + "]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, body.path("requestedCount").asInt());
        assertEquals(2, body.path("boundCount").asInt());
        assertEquals(2, countRows("batch_certificate_binding"));
    }

    @Test
    void batchBindAnyInvalid_wholeRollback_failureDoesNotOccupyKey() throws Exception {
        String decl = publishDefault();
        String withdrawnDecl = publishDefault();
        withdraw(withdrawnDecl, 200);
        String b1 = "BK-RB1-" + unique();
        String b2 = "BK-RB2-" + unique();
        String b3 = "BK-RB3-" + unique();
        createBatch(b1, "PROD-1", "LOT-1", 201);
        createBatch(b2, "PROD-1", "LOT-2", 201);
        createBatch(b3, "PROD-1", "LOT-3", 201);

        // 中间一条引用已撤回声明 → 整次 422，三批均无绑定
        MvcResult failed = mockMvc.perform(post("/api/certificates/bindings/batch")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BB-F\",\"bindings\":["
                                + bindItem(b1, decl) + "," + bindItem(b2, withdrawnDecl) + ","
                                + bindItem(b3, decl) + "]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals("DECLARATION_WITHDRAWN", objectMapper
                .readTree(failed.getResponse().getContentAsString()).path("code").asText());
        assertEquals(0, countRows("batch_certificate_binding"), "失败回滚后查询不到半成品");
        assertTrue(certificate(b1).path("binding").isNull());
        assertTrue(certificate(b3).path("binding").isNull());

        // 失败不占键：同一 commandKey 修正参数后成功
        mockMvc.perform(post("/api/certificates/bindings/batch")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BB-F\",\"bindings\":["
                                + bindItem(b1, decl) + "," + bindItem(b2, decl) + "]}"))
                .andExpect(status().isCreated());
        assertEquals(2, countRows("batch_certificate_binding"));
    }
    // ---------- 放行门禁与快照 ----------

    @Test
    void releaseWithoutBinding_returns422_batchStaysInReview() throws Exception {
        String batchKey = "BK-NOGATE-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        passTest(batchKey);
        approve(batchKey, "qa-1", "QUALITY", 201);
        // 未绑定声明，第二笔批准触发门禁 → 422，批次停留 RELEASE_REVIEW
        JsonNode error = objectMapper.readTree(approve(batchKey, "ops-1", "OPERATIONS", 422)
                .getResponse().getContentAsString());
        assertEquals("CERTIFICATE_BINDING_REQUIRED", error.path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0, countRows("release_snapshot"));
        // 补绑后可放行
        bind(batchKey, publishDefault(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseWithBinding_snapshotFrozen_withdrawAfterReleaseKeepsHistory() throws Exception {
        String batchKey = "BK-SNAP-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        passTest(batchKey);
        String decl = publishDefault();
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode snapshot = certificate(batchKey).path("releaseSnapshot");
        assertEquals(decl, snapshot.path("declarationKey").asText());
        assertEquals(1, snapshot.path("declarationVersion").asInt());
        assertEquals("LOT-", snapshot.path("matchedPrefix").asText());
        String releasedAt = snapshot.path("releasedAt").asText();
        assertFalse(releasedAt.isBlank());

        // 撤回只影响后续放行：快照与历史不改写
        withdraw(decl, 200);
        JsonNode after = certificate(batchKey).path("releaseSnapshot");
        assertEquals(decl, after.path("declarationKey").asText());
        assertEquals(releasedAt, after.path("releasedAt").asText());
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(1, countRows("release_snapshot"));
    }

    @Test
    void withdrawBetweenBindAndRelease_releaseBlocked422() throws Exception {
        String batchKey = "BK-WBR-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        passTest(batchKey);
        String decl = publishDefault();
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        withdraw(decl, 200);
        // 放行时点声明已撤回 → 422，批次停留 RELEASE_REVIEW，无快照
        JsonNode error = objectMapper.readTree(approve(batchKey, "ops-1", "OPERATIONS", 422)
                .getResponse().getContentAsString());
        assertEquals("DECLARATION_WITHDRAWN", error.path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0, countRows("release_snapshot"));
        // 换绑有效声明后可放行
        bind(batchKey, publishDefault(), 200);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void expiryBetweenBindAndRelease_releaseBlocked422() throws Exception {
        String batchKey = "BK-EXP2-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        passTest(batchKey);
        // 有效窗口约 2 秒：绑定与首批准在窗口内，第二批准时窗口已过
        String decl = "DECL-SHORT-" + unique();
        publish(decl, "PROD-1", "LOT-", 9100, "2020-01-01T00:00:00Z",
                Instant.now().plusSeconds(2).toString(), 201);
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        Thread.sleep(2500);
        JsonNode error = objectMapper.readTree(approve(batchKey, "ops-1", "OPERATIONS", 422)
                .getResponse().getContentAsString());
        assertEquals("DECLARATION_NOT_EFFECTIVE", error.path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0, countRows("release_snapshot"));
    }

    // ---------- 绑定幂等 ----------

    @Test
    void bindCommandKey_sameParamsReplay_changedParams409() throws Exception {
        String batchKey = "BK-BIDEM-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-1", 201);
        String decl = publishDefault();
        String body = bindBody("CK-BIND-1", batchKey, decl);
        MvcResult first = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countRows("batch_certificate_binding"));
        // 同 commandKey 换声明 → 409
        String otherDecl = publishDefault();
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-BIND-1", batchKey, otherDecl)))
                .andExpect(status().isConflict());
        // 换 commandKey 换绑 → 200 替换
        bind(batchKey, otherDecl, 200);
        assertEquals(otherDecl, certificate(batchKey).path("binding")
                .path("declarationKey").asText());
        assertEquals(1, countRows("batch_certificate_binding"));
    }

    // ---------- 拆分继承 ----------

    @Test
    void splitChildren_inheritBinding_andCanReleaseWithInheritedReference() throws Exception {
        String parent = "BK-INH-P-" + unique();
        createBatch(parent, "PROD-1", "LOT-1", 201);
        String decl = publishDefault();
        releaseBatch(parent, decl);

        String c1 = "BK-INH-C1-" + unique();
        String c2 = "BK-INH-C2-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SP-" + unique() + "\",\"children\":["
                                + "{\"batchKey\":\"" + c1 + "\",\"batchNo\":\"LOT-C1\"},"
                                + "{\"batchKey\":\"" + c2 + "\",\"batchNo\":\"LOT-C2\"}]}"))
                .andExpect(status().isCreated());

        // 子批继承父批合格证引用
        for (String child : new String[]{c1, c2}) {
            JsonNode cert = certificate(child);
            assertEquals(decl, cert.path("binding").path("declarationKey").asText());
            assertTrue(cert.path("releaseSnapshot").isNull());
        }
        // 子批凭继承的引用完成检验与双角色批准后放行，快照固化同一声明
        passTest(c1);
        approve(c1, "qa-1", "QUALITY", 201);
        approve(c1, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(c1));
        assertEquals(decl, certificate(c1).path("releaseSnapshot")
                .path("declarationKey").asText());
    }
    // ---------- helpers ----------

    private int declSeq = 0;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * 发布一个对 PROD-1、前缀 LOT- 长期有效的声明，优先级递增避免重叠冲突。
     */
    private String publishDefault() throws Exception {
        declSeq++;
        String declKey = "DECL-" + unique();
        publish(declKey, "PROD-1", "LOT-", declSeq, "2020-01-01T00:00:00Z",
                "2030-01-01T00:00:00Z", 201);
        return declKey;
    }

    private void publish(String declKey, String material, String prefix, int priority,
                         String validFrom, String validTo, int expected) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-P-" + unique() + "\",\"declarationKey\":\""
                                + declKey + "\",\"version\":1,\"materialCode\":\"" + material
                                + "\",\"batchNoPrefix\":\"" + prefix + "\",\"priority\":"
                                + priority + ",\"validFrom\":\"" + validFrom
                                + "\",\"validTo\":\"" + validTo + "\"}"))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declKey, int expected) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations/" + declKey + "/versions/1/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-W-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private void createBatch(String batchKey, String productCode, String batchNo, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"" + productCode
                                + "\",\"batchNo\":\"" + batchNo
                                + "\",\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().is(expected));
    }

    private void passTest(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"t1\",\"result\":\"PASS\","
                                + "\"inspector\":\"insp-1\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult bind(String batchKey, String declKey, int expected) throws Exception {
        return mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "binder-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-B-" + unique(), batchKey, declKey)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String bindBody(String commandKey, String batchKey, String declKey) {
        return "{\"commandKey\":\"" + commandKey + "\",\"batchKey\":\"" + batchKey
                + "\",\"declarationKey\":\"" + declKey + "\",\"declarationVersion\":1}";
    }

    private String bindItem(String batchKey, String declKey) {
        return "{\"batchKey\":\"" + batchKey + "\",\"declarationKey\":\"" + declKey
                + "\",\"declarationVersion\":1}";
    }

    /**
     * 绑定声明并完成双角色批准使批次进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String decl) throws Exception {
        passTest(batchKey);
        bind(batchKey, decl, 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private JsonNode certificate(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }
}