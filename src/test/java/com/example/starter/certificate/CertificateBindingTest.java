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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次合格证绑定与放行门禁测试：绑定主流程与 422 分支、批量绑定整次回滚、
 * 放行门禁与快照固化、撤回只影响后续放行、拆分继承、幂等与并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateBindingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private int declarationPriority;

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

    // ---------- 绑定主流程 ----------

    @Test
    void bindHappy_certificateQueryAndHistoryExposeBinding() throws Exception {
        String batchKey = "BK-B-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-B1", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);

        MvcResult bound = bind(batchKey, "DECL-B1", 1, 201);
        JsonNode body = objectMapper.readTree(bound.getResponse().getContentAsString());
        assertEquals(batchKey, body.path("batchKey").asText());
        assertEquals("DECL-B1", body.path("declarationKey").asText());
        assertEquals(1, body.path("declarationVersion").asInt());
        assertEquals("LOT", body.path("matchedPrefix").asText());
        assertEquals("sup-1", body.path("boundBy").asText());

        // 明细查询：绑定存在、快照为 null（未放行）
        JsonNode cert = certificateJson(batchKey);
        assertEquals("DECL-B1", cert.path("binding").path("declarationKey").asText());
        assertEquals("ACTIVE", cert.path("binding").path("declarationStatus").asText());
        assertTrue(cert.path("releaseSnapshot").isNull());

        // 批次历史：certificate 非空、releaseSnapshot 为 null，既有字段不受影响
        JsonNode history = historyJson(batchKey);
        assertEquals("DECL-B1", history.path("certificate").path("declarationKey").asText());
        assertTrue(history.path("releaseSnapshot").isNull());
        assertEquals("QUARANTINED", history.path("batch").path("status").asText());
    }

    // ---------- 绑定 422 / 404 分支 ----------

    @Test
    void bindValidationFailures_return422Or404_andStoreNothing() throws Exception {
        String batchKey = "BK-BV-" + unique();
        createBatch(batchKey, List.of("t1"));
        // 物料不匹配
        publishDecl("DECL-MM", 1, "OTHER-MAT", List.of("LOT"), wideFrom(), wideTo(), 201);
        assertBindError(batchKey, "DECL-MM", 1, 422, "MATERIAL_MISMATCH");
        // 批号前缀不匹配
        publishDecl("DECL-PM", 1, "PROD-1", List.of("ZZZ"), wideFrom(), wideTo(), 201);
        assertBindError(batchKey, "DECL-PM", 1, 422, "PREFIX_MISMATCH");
        // 已撤回
        publishDecl("DECL-WD", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        withdraw("DECL-WD", 1, 200);
        assertBindError(batchKey, "DECL-WD", 1, 422, "DECLARATION_WITHDRAWN");
        // 已过期
        publishDecl("DECL-EXP", 1, "PROD-1", List.of("LOT"),
                "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", 201);
        assertBindError(batchKey, "DECL-EXP", 1, 422, "DECLARATION_EXPIRED");
        // 尚未生效
        publishDecl("DECL-FUT", 1, "PROD-1", List.of("LOT"),
                "2099-01-01T00:00:00Z", "2099-02-01T00:00:00Z", 201);
        assertBindError(batchKey, "DECL-FUT", 1, 422, "DECLARATION_NOT_YET_VALID");
        // 批次不存在 404、声明不存在 404
        bind("NO-SUCH-BATCH", "DECL-MM", 1, 404);
        bind(batchKey, "NO-SUCH-DECL", 1, 404);
        // 全部失败均未留下绑定
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));
        assertTrue(certificateJson(batchKey).path("binding").isNull());
    }

    // ---------- 换绑语义 ----------

    @Test
    void rebind_sameVersion200_replaceBeforeRelease_conflictAfterRelease() throws Exception {
        String batchKey = "BK-RB-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-R1", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        publishDecl("DECL-R2", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);

        bind(batchKey, "DECL-R1", 1, 201);
        // 同版本重复绑定（新命令键）→ 200 原绑定
        MvcResult again = bind(batchKey, "DECL-R1", 1, 200);
        assertEquals("DECL-R1", objectMapper.readTree(again.getResponse().getContentAsString())
                .path("declarationKey").asText());
        // 放行前换绑 → 200 替换
        bind(batchKey, "DECL-R2", 1, 200);
        assertEquals("DECL-R2", certificateJson(batchKey).path("binding")
                .path("declarationKey").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));

        // 放行后不得换绑 → 409
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        bind(batchKey, "DECL-R1", 1, 409);
        assertEquals("DECL-R2", certificateJson(batchKey).path("binding")
                .path("declarationKey").asText());
    }

    // ---------- 批量绑定：整次回滚 ----------

    @Test
    void batchBind_allOrNothing_countsAndRollback() throws Exception {
        String b1 = "BK-BB-1-" + unique();
        String b2 = "BK-BB-2-" + unique();
        String b3 = "BK-BB-3-" + unique();
        createBatch(b1, List.of("t1"));
        createBatch(b2, List.of("t1"));
        createBatch(b3, List.of("t1"));
        publishDecl("DECL-BB", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);

        // 全部有效 → 201，数量字段返回要求值与实际值
        MvcResult ok = batchBind("CK-BB-1",
                List.of(new String[]{b1, "DECL-BB"}, new String[]{b2, "DECL-BB"},
                        new String[]{b3, "DECL-BB"}), 201);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertEquals(3, body.path("requestedCount").asInt());
        assertEquals(3, body.path("boundCount").asInt());
        assertEquals(3, body.path("bindings").size());

        // 任一失效（物料不匹配）→ 整次 422，可区分原因码，查询不到半成品
        String b4 = "BK-BB-4-" + unique();
        String b5 = "BK-BB-5-" + unique();
        createBatch(b4, List.of("t1"));
        createBatch(b5, List.of("t1"));
        publishDecl("DECL-BB-OTHER", 1, "OTHER-MAT", List.of("LOT"), wideFrom(), wideTo(), 201);
        MvcResult failed = batchBind("CK-BB-2",
                List.of(new String[]{b4, "DECL-BB"}, new String[]{b5, "DECL-BB-OTHER"}), 422);
        assertEquals("MATERIAL_MISMATCH", objectMapper
                .readTree(failed.getResponse().getContentAsString()).path("code").asText());
        assertTrue(certificateJson(b4).path("binding").isNull());
        assertTrue(certificateJson(b5).path("binding").isNull());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));

        // 批次不存在 → 404，同样整次回滚
        batchBind("CK-BB-3",
                List.of(new String[]{b4, "DECL-BB"}, new String[]{"NO-SUCH-BATCH", "DECL-BB"}),
                404);
        assertTrue(certificateJson(b4).path("binding").isNull());

        // 失效声明（已过期）→ 422 整次回滚
        publishDecl("DECL-BB-EXP", 1, "PROD-1", List.of("LOT"),
                "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", 201);
        MvcResult expired = batchBind("CK-BB-4",
                List.of(new String[]{b4, "DECL-BB"}, new String[]{b5, "DECL-BB-EXP"}), 422);
        assertEquals("DECLARATION_EXPIRED", objectMapper
                .readTree(expired.getResponse().getContentAsString()).path("code").asText());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseGating_noBinding422_thenBindAndReleaseWithSnapshot() throws Exception {
        String batchKey = "BK-RG-" + unique();
        createBatch(batchKey, List.of("t1"));
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 未绑定 → 第二批准 422，批次停留 RELEASE_REVIEW，不产生批准与快照
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", 422);
        assertEquals("BINDING_REQUIRED", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot", Integer.class));

        // 绑定后放行成功，快照固化声明版本、匹配前缀与时点
        publishDecl("DECL-RG", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(batchKey, "DECL-RG", 1, 201);
        approve(batchKey, "ops-2", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode history = historyJson(batchKey);
        JsonNode snapshot = history.path("releaseSnapshot");
        assertEquals("DECL-RG", snapshot.path("declarationKey").asText());
        assertEquals(1, snapshot.path("declarationVersion").asInt());
        assertEquals("LOT", snapshot.path("matchedPrefix").asText());
        assertFalse(snapshot.path("releasedAt").isNull());
        JsonNode cert = certificateJson(batchKey);
        assertEquals("DECL-RG", cert.path("releaseSnapshot").path("declarationKey").asText());
    }

    // ---------- 撤回只影响后续放行 ----------

    @Test
    void withdrawAfterBind_blocksRelease_rebindNewVersionReleases() throws Exception {
        String batchKey = "BK-WB-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-WB", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(batchKey, "DECL-WB", 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", 201);

        // 绑定后撤回 → 放行被 422 拦截
        withdraw("DECL-WB", 1, 200);
        MvcResult rejected = approve(batchKey, "ops-1", "OPERATIONS", 422);
        assertEquals("DECLARATION_WITHDRAWN", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot", Integer.class));

        // 发布同业务键新版本并换绑 → 放行成功
        publishDecl("DECL-WB", 2, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(batchKey, "DECL-WB", 2, 200);
        approve(batchKey, "ops-2", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(2, historyJson(batchKey).path("releaseSnapshot")
                .path("declarationVersion").asInt());
    }

    @Test
    void withdrawAfterRelease_doesNotRewriteSnapshotOrHistory() throws Exception {
        String batchKey = "BK-WA-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-WA", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(batchKey, "DECL-WA", 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        JsonNode before = historyJson(batchKey).path("releaseSnapshot");
        // 撤回只影响后续放行：快照、批次状态与历史不改写
        withdraw("DECL-WA", 1, 200);
        JsonNode after = historyJson(batchKey).path("releaseSnapshot");
        assertEquals(before, after);
        assertEquals("DECL-WA", after.path("declarationKey").asText());
        assertEquals("LOT", after.path("matchedPrefix").asText());
        assertEquals("RELEASED", currentStatus(batchKey));
        // 绑定记录本身不改写；声明实时状态可见为 WITHDRAWN
        JsonNode cert = certificateJson(batchKey);
        assertEquals("DECL-WA", cert.path("binding").path("declarationKey").asText());
        assertEquals("WITHDRAWN", cert.path("binding").path("declarationStatus").asText());
        assertEquals("DECL-WA", cert.path("releaseSnapshot").path("declarationKey").asText());
    }

    // ---------- 拆分继承 ----------

    @Test
    void splitChildren_inheritBinding_prefixRecomputedPerChildBatchNo() throws Exception {
        String parent = "BK-SI-P-" + unique();
        createBatch(parent, List.of("t1"));
        publishDecl("DECL-SI", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(parent, "DECL-SI", 1, 201);
        submitTest(parent, "t1", "PASS", "insp-1", 201);
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        String childMatch = "BK-SI-C1-" + unique();
        String childMiss = "BK-SI-C2-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SI-S\",\"children\":["
                                + "{\"batchKey\":\"" + childMatch + "\",\"batchNo\":\"LOT-C1\"},"
                                + "{\"batchKey\":\"" + childMiss + "\",\"batchNo\":\"XX-C2\"}]}"))
                .andExpect(status().isCreated());

        // 子批继承声明引用；批号匹配的子批前缀重算为 LOT，不匹配者为 null
        JsonNode cert1 = certificateJson(childMatch);
        assertEquals("DECL-SI", cert1.path("binding").path("declarationKey").asText());
        assertEquals("LOT", cert1.path("binding").path("matchedPrefix").asText());
        JsonNode cert2 = certificateJson(childMiss);
        assertEquals("DECL-SI", cert2.path("binding").path("declarationKey").asText());
        assertTrue(cert2.path("binding").path("matchedPrefix").isNull());

        // 匹配子批凭继承的绑定完成放行
        submitTest(childMatch, "t1", "PASS", "insp-2", 201);
        approve(childMatch, "qa-2", "QUALITY", 201);
        approve(childMatch, "ops-2", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(childMatch));
        assertEquals("DECL-SI", historyJson(childMatch).path("releaseSnapshot")
                .path("declarationKey").asText());

        // 不匹配子批放行时被 422 拦截
        submitTest(childMiss, "t1", "PASS", "insp-3", 201);
        approve(childMiss, "qa-3", "QUALITY", 201);
        MvcResult rejected = approve(childMiss, "ops-3", "OPERATIONS", 422);
        assertEquals("PREFIX_MISMATCH", objectMapper
                .readTree(rejected.getResponse().getContentAsString()).path("code").asText());
    }

    // ---------- 绑定幂等 ----------

    @Test
    void bindCommandKey_replayReturnsFirstSnapshot_changedParams409_failureDoesNotOccupyKey()
            throws Exception {
        String batchKey = "BK-BI-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-BI-1", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        publishDecl("DECL-BI-2", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);

        String body = "{\"commandKey\":\"CK-BI-1\",\"declarationKey\":\"DECL-BI-1\",\"version\":1}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));

        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BI-1\",\"declarationKey\":\"DECL-BI-2\","
                                + "\"version\":1}"))
                .andExpect(status().isConflict());

        // 失败不占键：绑定不存在声明 404 后，同键修正参数成功
        String bad = "{\"commandKey\":\"CK-BI-2\",\"declarationKey\":\"NO-SUCH\",\"version\":1}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isNotFound());
        String fixed = "{\"commandKey\":\"CK-BI-2\",\"declarationKey\":\"DECL-BI-2\",\"version\":1}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isOk());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentWithdrawAndFinalApproval_commitOrderDecides() throws Exception {
        String batchKey = "BK-CW-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-CW", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        bind(batchKey, "DECL-CW", 1, 201);
        submitTest(batchKey, "t1", "PASS", "insp-1", 201);
        approve(batchKey, "qa-1", "QUALITY", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/declarations/DECL-CW/versions/1/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-W\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-A\"}"))
        );
        int withdraw = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, withdraw, "撤回始终成功且只影响后续放行");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?",
                Integer.class, batchKey);
        if (approval == 201) {
            // 放行先提交：批次 RELEASED 且快照固化，随后撤回不改写
            assertEquals("RELEASED", currentStatus(batchKey));
            assertEquals(1, snapshots);
            assertEquals("DECL-CW", historyJson(batchKey).path("releaseSnapshot")
                    .path("declarationKey").asText());
        } else {
            // 撤回先提交：放行被 422 拦截，批次停留 RELEASE_REVIEW，无快照
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            assertEquals(0, snapshots);
        }
    }

    @Test
    void concurrentSameBindCommandKey_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-CB-" + unique();
        createBatch(batchKey, List.of("t1"));
        publishDecl("DECL-CB", 1, "PROD-1", List.of("LOT"), wideFrom(), wideTo(), 201);
        String body = "{\"commandKey\":\"CK-CB-1\",\"declarationKey\":\"DECL-CB\",\"version\":1}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String wideFrom() {
        return "2020-01-01T00:00:00Z";
    }

    private String wideTo() {
        return "2099-01-01T00:00:00Z";
    }

    private void createBatch(String batchKey, List<String> items) throws Exception {
        String tests = items.stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(","));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"PROD-1\",\"batchNo\":\"LOT-1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":["
                                + tests + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishDecl(String declarationKey, int version, String materialCode,
                             List<String> prefixes, String validFrom, String validTo, int expected)
            throws Exception {
        String pfx = prefixes.stream().map(p -> "\"" + p + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PUB-" + unique() + "\",\"declarationKey\":\""
                                + declarationKey + "\",\"version\":" + version
                                + ",\"supplierId\":\"SUP-1\",\"materialCode\":\"" + materialCode
                                + "\",\"prefixes\":[" + pfx + "],\"priority\":"
                                + (++declarationPriority) + ",\"validFrom\":\"" + validFrom
                                + "\",\"validTo\":\"" + validTo + "\"}"))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declarationKey, int version, int expected) throws Exception {
        mockMvc.perform(post("/api/declarations/" + declarationKey + "/versions/" + version
                        + "/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-WD-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private MvcResult bind(String batchKey, String declarationKey, int version, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-binding")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BIND-" + unique()
                                + "\",\"declarationKey\":\"" + declarationKey
                                + "\",\"version\":" + version + "}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void assertBindError(String batchKey, String declarationKey, int version,
                                 int expectedStatus, String expectedCode) throws Exception {
        MvcResult result = bind(batchKey, declarationKey, version, expectedStatus);
        assertEquals(expectedCode, objectMapper
                .readTree(result.getResponse().getContentAsString()).path("code").asText());
    }

    private MvcResult batchBind(String commandKey, List<String[]> items, int expected)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(items.get(i)[0])
                    .append("\",\"declarationKey\":\"").append(items.get(i)[1])
                    .append("\",\"version\":1}");
        }
        return mockMvc.perform(post("/api/certificate-bindings/batch")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.append("]}").toString()))
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

    private MvcResult approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        return historyJson(batchKey).path("batch").path("status").asText();
    }

    private JsonNode historyJson(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode certificateJson(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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