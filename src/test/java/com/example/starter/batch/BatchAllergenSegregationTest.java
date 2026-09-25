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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次过敏原隔离与合批放行联合校验测试：成分版本、换序同参与 allergenKey 幂等、
 * 合批兼容矩阵与整体回滚、放行门禁、召回后代隔离级别约束、ALLERGEN_RISK 解除与并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchAllergenSegregationTest {

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
        jdbc.update("DELETE FROM merge_lineage");
        jdbc.update("DELETE FROM composition_version");
        jdbc.update("DELETE FROM allergen_risk");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 成分版本主流程 ----------

    @Test
    void reviseComposition_appendsImmutableVersions_andNormalizesCodeOrder() throws Exception {
        String batch = "BK-AL-R-" + unique();
        createBatch(batch, List.of("t1"), 201);

        // 初始成分版本 v1：无过敏原、NONE、未检验
        JsonNode v1 = compositions(batch);
        assertEquals(1, v1.size());
        assertEquals(1, v1.get(0).path("version").asInt());
        assertEquals(0, v1.get(0).path("allergenCodes").size());
        assertEquals("NONE", v1.get(0).path("segregationLevel").asText());
        assertFalse(v1.get(0).path("tested").asBoolean());

        // PASS 检验后 v1 视为已检验
        submitTest(batch, "t1", "PASS", "insp-1", 201);
        assertTrue(compositions(batch).get(0).path("tested").asBoolean());

        // 换序、重复、大小写混合的代码集合规范化为同一版本内容
        MvcResult revised = revise(batch, "AK-R1", 1,
                List.of("milk", "EGG", "Milk"), "SEGREGATED", 201);
        JsonNode body = objectMapper.readTree(revised.getResponse().getContentAsString());
        assertEquals(2, body.path("version").asInt());
        assertEquals("SEGREGATED", body.path("segregationLevel").asText());
        assertEquals(List.of("EGG", "MILK"), objectMapper.convertValue(
                body.path("allergenCodes"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertFalse(body.path("tested").asBoolean());

        // 成分版本不可变：v1 保持原内容，v2 为新版本
        JsonNode all = compositions(batch);
        assertEquals(2, all.size());
        assertEquals("NONE", all.get(0).path("segregationLevel").asText());
        assertEquals(2, all.get(1).path("version").asInt());
    }

    @Test
    void reviseComposition_orderInsensitiveReplay_changedParamsConflict_failureDoesNotOccupyKey()
            throws Exception {
        String batch = "BK-AL-IDEM-" + unique();
        createBatch(batch, List.of("t1"), 201);

        // 未知代码 422，失败不占键
        revise(batch, "AK-IDEM", 1, List.of("NO_SUCH_CODE"), "NONE", 422);
        // 空级别与未知级别 422
        revise(batch, "AK-IDEM2", 1, List.of("MILK"), "", 422);
        revise(batch, "AK-IDEM3", 1, List.of("MILK"), "SUPER", 422);
        assertEquals(1, compositions(batch).size(), "失败修订不得产生成分版本");

        // 同一 allergenKey 修正参数后成功（失败未占键）
        revise(batch, "AK-IDEM", 1, List.of("MILK", "EGG"), "SEGREGATED", 201);
        // 同键同参（换序）重放：返回首次结果，不追加新版本
        MvcResult replay = revise(batch, "AK-IDEM", 1, List.of("EGG", "MILK"), "SEGREGATED", 201);
        assertEquals(2, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("version").asInt());
        assertEquals(2, compositions(batch).size());
        // 同键改参 409
        revise(batch, "AK-IDEM", 1, List.of("MILK", "SOY"), "SEGREGATED", 409);
        // expectedVersion 不一致 409
        revise(batch, "AK-IDEM4", 1, List.of("MILK"), "NONE", 409);
        revise(batch, "AK-IDEM4", 2, List.of("MILK"), "NONE", 201);
    }

    @Test
    void reviseComposition_unknownBatch404_andTerminalStatuses409() throws Exception {
        revise("NO-SUCH-BATCH", "AK-404", 1, List.of("MILK"), "NONE", 404);

        String rejected = "BK-AL-RJ-" + unique();
        createBatch(rejected, List.of("t1"), 201);
        submitTest(rejected, "t1", "FAIL", "insp", 201);
        revise(rejected, "AK-RJ", 1, List.of("MILK"), "NONE", 409);

        String recalled = "BK-AL-RC-" + unique();
        createBatch(recalled, List.of("t1"), 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "质量问题", "CK-RC-" + unique(), 201);
        revise(recalled, "AK-RC", 1, List.of("MILK"), "NONE", 409);
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseGate_untestedCompositionVersionBlocksRelease_untilRetested() throws Exception {
        String batch = "BK-AL-GATE-" + unique();
        createBatch(batch, List.of("t1"), 201);
        submitTest(batch, "t1", "PASS", "insp-1", 201);
        assertEquals("PENDING_RELEASE", currentStatus(batch));

        // 放行前修订成分：新版本 v2 未检验
        revise(batch, "AK-G1", 1, List.of("MILK"), "NONE", 201);
        approve(batch, "qa-1", "QUALITY", "CK-G-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));
        // 任一未检验成分版本阻断放行
        approve(batch, "ops-1", "OPERATIONS", "CK-G-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 对新版本重新检验后放行成功
        submitTest(batch, "t1", "PASS", "insp-2", 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-G-A3", 201);
        assertEquals("RELEASED", currentStatus(batch));
    }

    // ---------- 合批 ----------

    @Test
    void merge_sameLevelSources_createsTargetAndRetiresSources() throws Exception {
        String s1 = "BK-MG-S1-" + unique();
        String s2 = "BK-MG-S2-" + unique();
        createBatch(s1, List.of("t1"), 201);
        releaseBatch(s1, "insp-1");
        createBatch(s2, List.of("t2"), 201);
        releaseBatch(s2, "insp-2");

        String target = "BK-MG-T-" + unique();
        MvcResult result = merge("AK-MG-1", target, "CONT-STRICT", List.of(s1, s2), 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(target, body.path("targetBatchKey").asText());
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals("CONT-STRICT", body.path("containerKey").asText());
        assertEquals(2, body.path("sourceBatchKeys").size());
        assertEquals("NONE", body.path("segregationLevel").asText());

        // 来源退出可用库存（MERGED），目标初始隔离
        assertEquals("MERGED", currentStatus(s1));
        assertEquals("MERGED", currentStatus(s2));
        List<String> available = availableKeys();
        assertFalse(available.contains(s1));
        assertFalse(available.contains(s2));
        assertTrue(available.contains(target));

        // 目标继承必做检验项并集与成分 v1
        JsonNode targetCompositions = compositions(target);
        assertEquals(1, targetCompositions.size());
        assertFalse(targetCompositions.get(0).path("tested").asBoolean());

        // 血缘快照：直接合批来源与传递祖先
        JsonNode lineage = lineageSnapshot(target);
        assertEquals(2, lineage.path("mergeSources").size());
        assertEquals(2, lineage.path("ancestors").size());

        // 目标完成检验与双角色批准后放行（血缘集合内成分版本均已检验）
        releaseBatch(target, "insp-3");
        assertEquals("RELEASED", currentStatus(target));
    }

    @Test
    void merge_differentLevels_requiresContainerCompatibilityMatrix() throws Exception {
        String none = "BK-MX-N-" + unique();
        createBatch(none, List.of("t1"), 201);
        releaseBatch(none, "insp-1");
        String segregated = "BK-MX-S-" + unique();
        createBatch(segregated, List.of("t1"), 201);
        revise(segregated, "AK-MX-S", 1, List.of("MILK"), "SEGREGATED", 201);
        releaseBatch(segregated, "insp-2");

        // 严格容器未声明矩阵 → 422，且来源状态不变、无目标批次、无血缘
        String t1 = "BK-MX-T1-" + unique();
        merge("AK-MX-1", t1, "CONT-STRICT", List.of(none, segregated), 422);
        // 部分兼容容器仅声明 SEGREGATED×ISOLATED → 仍 422
        merge("AK-MX-2", t1, "CONT-SEG-ISO", List.of(none, segregated), 422);
        assertEquals("RELEASED", currentStatus(none));
        assertEquals("RELEASED", currentStatus(segregated));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, t1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));

        // 全兼容容器声明了 NONE×SEGREGATED → 201，目标成分取并集与最高级别
        String t2 = "BK-MX-T2-" + unique();
        MvcResult ok = merge("AK-MX-3", t2, "CONT-OPEN", List.of(none, segregated), 201);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertEquals("SEGREGATED", body.path("segregationLevel").asText());
        assertEquals(List.of("MILK"), objectMapper.convertValue(body.path("allergenCodes"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // SEGREGATED×ISOLATED 在部分兼容容器声明矩阵内 → 201
        String iso = "BK-MX-I-" + unique();
        createBatch(iso, List.of("t1"), 201);
        revise(iso, "AK-MX-I", 1, List.of("EGG"), "ISOLATED", 201);
        releaseBatch(iso, "insp-3");
        String seg2 = "BK-MX-S2-" + unique();
        createBatch(seg2, List.of("t1"), 201);
        revise(seg2, "AK-MX-S2", 1, List.of("SOY"), "SEGREGATED", 201);
        releaseBatch(seg2, "insp-4");
        merge("AK-MX-4", "BK-MX-T3-" + unique(), "CONT-SEG-ISO", List.of(iso, seg2), 201);
    }

    @Test
    void merge_anySourceIneligible_rollsBackAllLineageAndInventory() throws Exception {
        String released1 = "BK-MR-R1-" + unique();
        String released2 = "BK-MR-R2-" + unique();
        String quarantined = "BK-MR-Q-" + unique();
        createBatch(released1, List.of("t1"), 201);
        releaseBatch(released1, "insp-1");
        createBatch(released2, List.of("t1"), 201);
        releaseBatch(released2, "insp-2");
        createBatch(quarantined, List.of("t1"), 201);

        String target = "BK-MR-T-" + unique();
        // 任一来源非 RELEASED → 整次 409，全部血缘与库存回滚
        merge("AK-MR-1", target, "CONT-STRICT", List.of(released1, released2, quarantined), 409);
        assertEquals("RELEASED", currentStatus(released1));
        assertEquals("RELEASED", currentStatus(released2));
        assertEquals("QUARANTINED", currentStatus(quarantined));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, target));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));
        List<String> available = availableKeys();
        assertTrue(available.contains(released1));
        assertTrue(available.contains(released2));

        // 来源当前成分版本未检验 → 422，同样整体回滚
        revise(released2, "AK-MR-LVL", 1, List.of(), "SEGREGATED", 201);
        merge("AK-MR-2", target, "CONT-OPEN", List.of(released1, released2), 422);
        assertEquals("RELEASED", currentStatus(released1));
        assertEquals("RELEASED", currentStatus(released2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));

        // 请求内来源重复 / 目标即来源 → 409；未知容器 → 404；未知来源 → 404
        merge("AK-MR-3", target, "CONT-OPEN", List.of(released1, released1), 409);
        merge("AK-MR-4", released1, "CONT-OPEN", List.of(released1, released2), 409);
        merge("AK-MR-5", target, "NO-SUCH-CONT", List.of(released1, released2), 404);
        merge("AK-MR-6", target, "CONT-OPEN", List.of(released1, "NO-SUCH-BATCH"), 404);
    }

    @Test
    void mergeDiagnose_reportsPerSourceEligibilityAndMissingPairs() throws Exception {
        String none = "BK-MD-N-" + unique();
        createBatch(none, List.of("t1"), 201);
        releaseBatch(none, "insp-1");
        String isolated = "BK-MD-I-" + unique();
        createBatch(isolated, List.of("t1"), 201);
        revise(isolated, "AK-MD-I", 1, List.of("EGG"), "ISOLATED", 201);
        releaseBatch(isolated, "insp-2");
        String quarantined = "BK-MD-Q-" + unique();
        createBatch(quarantined, List.of("t1"), 201);

        MvcResult result = mockMvc.perform(post("/api/batches/merge/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"containerKey\":\"CONT-STRICT\",\"sourceBatchKeys\":[\""
                                + none + "\",\"" + isolated + "\",\"" + quarantined + "\"]}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(body.path("mergeable").asBoolean());
        assertEquals(List.of("NONE×ISOLATED"), objectMapper.convertValue(
                body.path("missingCompatibilityPairs"), objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        JsonNode sources = body.path("sources");
        assertEquals(3, sources.size());
        assertTrue(sources.get(0).path("eligible").asBoolean());
        assertTrue(sources.get(1).path("eligible").asBoolean());
        assertFalse(sources.get(2).path("eligible").asBoolean());
        assertTrue(sources.get(2).path("reason").asText().contains("QUARANTINED"));

        // 诊断不执行合并：无目标批次、无血缘、来源状态不变
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));
        assertEquals("RELEASED", currentStatus(none));

        // 未知容器 404
        mockMvc.perform(post("/api/batches/merge/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"containerKey\":\"NO-SUCH\",\"sourceBatchKeys\":[\"" + none
                                + "\"]}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 召回后代隔离级别约束 ----------

    @Test
    void recalledSource_descendantCannotLowerSegregationLevel() throws Exception {
        String parent = "BK-RS-P-" + unique();
        createBatch(parent, List.of("t1"), 201);
        revise(parent, "AK-RS-P", 1, List.of("MILK"), "SEGREGATED", 201);
        releaseBatch(parent, "insp-1");
        String child = "BK-RS-C-" + unique();
        split(parent, "CK-RS-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RS-C2-" + unique(), "L2"}), 201);
        // 子批继承父批成分：SEGREGATED + [MILK]
        JsonNode childComp = compositions(child);
        assertEquals("SEGREGATED", childComp.get(0).path("segregationLevel").asText());

        recall(parent, "u", "上游污染", "CK-RS-R", 201);

        // 召回来源的后代不得降低隔离级别 → 422；提高级别 → 201
        revise(child, "AK-RS-C1", 1, List.of("MILK"), "NONE", 422);
        assertEquals(1, compositions(child).size());
        revise(child, "AK-RS-C2", 1, List.of("MILK", "EGG"), "ISOLATED", 201);
        assertEquals("ISOLATED", compositions(child).get(1).path("segregationLevel").asText());
    }

    // ---------- ALLERGEN_RISK ----------

    @Test
    void releasedBatchNewAllergen_entersAllergenRisk_clearedByRetestAndDualApproval()
            throws Exception {
        String batch = "BK-AR-" + unique();
        createBatch(batch, List.of("t1"), 201);
        releaseBatch(batch, "insp-1");
        assertEquals("RELEASED", currentStatus(batch));

        // 已放行批次发现新增过敏原 → ALLERGEN_RISK，保留原放行快照
        revise(batch, "AK-AR-1", 1, List.of("PEANUT"), "SEGREGATED", 201);
        assertEquals("ALLERGEN_RISK", currentStatus(batch));
        JsonNode risk = risk(batch);
        assertTrue(risk.path("riskActive").asBoolean());
        assertTrue(risk.path("clearedAt").isNull());
        assertEquals("RELEASED", risk.path("releaseSnapshot").path("status").asText());
        assertEquals(2, risk.path("releaseSnapshot").path("approvals").size());

        // 未重新检验前批准 → 422
        approve(batch, "qa-2", "QUALITY", "CK-AR-A1", 422);
        // 重新检验当前成分版本
        submitTest(batch, "t1", "PASS", "insp-2", 201);
        assertEquals("ALLERGEN_RISK", currentStatus(batch));
        // 双角色放行：第一笔维持 ALLERGEN_RISK，第二笔解除
        MvcResult first = approve(batch, "qa-2", "QUALITY", "CK-AR-A2", 201);
        assertEquals("ALLERGEN_RISK", objectMapper.readTree(first.getResponse().getContentAsString())
                .path("batchStatus").asText());
        approve(batch, "ops-2", "OPERATIONS", "CK-AR-A3", 201);
        assertEquals("RELEASED", currentStatus(batch));

        // 风险解除，快照保留
        JsonNode cleared = risk(batch);
        assertFalse(cleared.path("riskActive").asBoolean());
        assertFalse(cleared.path("clearedAt").isNull());
        assertEquals(2, cleared.path("releaseSnapshot").path("approvals").size());
        assertTrue(availableKeys().contains(batch));

        // 历史快照：风险周期内 seq3 → ALLERGEN_RISK，seq4 → RELEASED
        MvcResult history = mockMvc.perform(get("/api/batches/" + batch + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode approvals = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("approvals");
        assertEquals(4, approvals.size());
        assertEquals("ALLERGEN_RISK", approvals.get(2).path("batchStatus").asText());
        assertEquals("RELEASED", approvals.get(3).path("batchStatus").asText());
    }

    @Test
    void reviseReleasedBatch_withoutNewAllergen_keepsReleased() throws Exception {
        String batch = "BK-KEEP-" + unique();
        createBatch(batch, List.of("t1"), 201);
        revise(batch, "AK-KP-1", 1, List.of("MILK"), "NONE", 201);
        releaseBatch(batch, "insp-1");
        // 仅提高隔离级别、不新增过敏原：保持 RELEASED，但新版本未检验会阻断后续合批
        revise(batch, "AK-KP-2", 2, List.of("MILK"), "SEGREGATED", 201);
        assertEquals("RELEASED", currentStatus(batch));
        assertFalse(risk(batch).path("riskActive").asBoolean());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentRevise_sameExpectedVersion_exactlyOneWins() throws Exception {
        String batch = "BK-CRV-" + unique();
        createBatch(batch, List.of("t1"), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(put("/api/batches/" + batch + "/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("AK-CRV-1", 1, List.of("MILK"), "SEGREGATED"))),
                () -> callStatus(put("/api/batches/" + batch + "/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody("AK-CRV-2", 1, List.of("EGG"), "ISOLATED")))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        // 按提交顺序裁决：恰一个 201，另一个看到版本已推进返回 409
        assertEquals(1, (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0),
                "并发同版本修订恰一个成功: " + first + "/" + second);
        assertTrue(first == 409 || second == 409);
        assertEquals(2, compositions(batch).size(), "仅胜出版本落库");
    }

    @Test
    void concurrentMerge_sameAllergenKey_replaysSingleResult() throws Exception {
        String s1 = "BK-CMG-S1-" + unique();
        String s2 = "BK-CMG-S2-" + unique();
        createBatch(s1, List.of("t1"), 201);
        releaseBatch(s1, "insp-1");
        createBatch(s2, List.of("t1"), 201);
        releaseBatch(s2, "insp-2");
        String target = "BK-CMG-T-" + unique();
        String body = "{\"allergenKey\":\"AK-CMG\",\"targetBatchKey\":\"" + target
                + "\",\"batchNo\":\"L1\",\"containerKey\":\"CONT-STRICT\",\"sourceBatchKeys\":[\""
                + s1 + "\",\"" + s2 + "\"]}";

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, target));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE target_key = ?", Integer.class, target));
        assertEquals("MERGED", currentStatus(s1));
        assertEquals("MERGED", currentStatus(s2));
    }

    // ---------- 查询边界 ----------

    @Test
    void compositionRiskLineageQueries_unknownBatchReturn404() throws Exception {
        mockMvc.perform(get("/api/batches/NO-SUCH/compositions")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/risk")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/lineage")).andExpect(status().isNotFound());

        // 无风险记录的批次：riskActive=false
        String batch = "BK-Q-" + unique();
        createBatch(batch, List.of("t1"), 201);
        JsonNode risk = risk(batch);
        assertFalse(risk.path("riskActive").asBoolean());
        assertTrue(risk.path("releaseSnapshot").isNull());
        JsonNode lineage = lineageSnapshot(batch);
        assertEquals(0, lineage.path("ancestors").size());
        assertEquals(0, lineage.path("mergeSources").size());
        assertEquals(1, lineage.path("currentComposition").path("version").asInt());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateCmd("CK-C-" + unique(), batchKey, "PROD-1", "LOT-1",
                                        Instant.parse("2026-01-02T03:04:05Z"), items))))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-T-" + unique(), "TK-" + unique(), item, result,
                                        inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private MvcResult approve(String batchKey, String actor, String role, String commandKey,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private String reviseBody(String allergenKey, int expectedVersion, List<String> codes,
                              String level) {
        StringBuilder sb = new StringBuilder("{\"allergenKey\":\"").append(allergenKey)
                .append("\",\"expectedVersion\":").append(expectedVersion)
                .append(",\"allergenCodes\":[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(codes.get(i)).append("\"");
        }
        return sb.append("],\"segregationLevel\":\"").append(level).append("\"}").toString();
    }

    private MvcResult revise(String batchKey, String allergenKey, int expectedVersion,
                             List<String> codes, String level, int expected) throws Exception {
        return mockMvc.perform(put("/api/batches/" + batchKey + "/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(allergenKey, expectedVersion, codes, level)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult merge(String allergenKey, String target, String container,
                            List<String> sources, int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"allergenKey\":\"").append(allergenKey)
                .append("\",\"targetBatchKey\":\"").append(target)
                .append("\",\"batchNo\":\"L1\",\"containerKey\":\"").append(container)
                .append("\",\"sourceBatchKeys\":[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(sources.get(i)).append("\"");
        }
        return mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.append("]}").toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult split(String parentKey, String commandKey, List<String[]> children,
                            int expected) throws Exception {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.append("]}").toString()))
                .andExpect(status().is(expected)).andReturn();
    }

    private JsonNode compositions(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/compositions"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode risk(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/risk"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode lineageSnapshot(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/lineage"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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
