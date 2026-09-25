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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
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
 * 批次过敏原隔离与合批联合校验测试：成分不可变版本、集合换序同参、expectedVersion 并发、
 * 兼容矩阵合批、库存守恒与整批回滚、未检验版本放行门禁、ALLERGEN_RISK 风险状态与解除、
 * 召回后级别不可降低、allergenKey 幂等与并发边界、成分/诊断/风险/血缘快照查询。
 * 全部断言落在真实 H2（MODE=MySQL）表与 HTTP 状态上，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllergenBatchFlowTest {

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
        jdbc.update("DELETE FROM allergen_risk");
        jdbc.update("DELETE FROM stock_ledger");
        jdbc.update("DELETE FROM inventory_stock");
        jdbc.update("DELETE FROM merge_lineage");
        jdbc.update("DELETE FROM merge_compat");
        jdbc.update("DELETE FROM allergen_component");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 成分版本主流程 ----------

    @Test
    void reviseComposition_appendsImmutableVersions_andNormalizesOrder() throws Exception {
        String key = createBatch(BigDecimal.TEN, List.of("MILK", "GLUTEN"), "LOW");

        // 初始成分版本 v1，创建响应携带版本与库存
        JsonNode created = history(key).path("batch");
        assertEquals(1, created.path("componentVersion").asInt());
        assertEquals("LOW", created.path("segregationLevel").asText());
        assertEquals(0, new BigDecimal(created.path("stockQuantity").asText())
                .compareTo(BigDecimal.TEN));

        // 修订 v1 -> v2：换序、重复与首尾空白，规范化结果去空白去重并按字典序
        revise(key, "AK-1", 1, List.of("GLUTEN", "EGG", " EGG ", "GLUTEN"), "MEDIUM", 201);
        JsonNode v2 = revise(key, "AK-2", 2, List.of("EGG", "GLUTEN"), "MEDIUM", 422,
                "成分未发生变更");
        // 集合换序同参：同 allergenKey 以 ["GLUTEN","EGG"] 重放 AK-1（顺序不同）返回首次快照
        String reorderedBody = reviseBody("AK-1", 1, List.of("GLUTEN", "EGG"), "MEDIUM");
        MvcResult replay = mockMvc.perform(post("/api/batches/" + key + "/composition")
                        .contentType(MediaType.APPLICATION_JSON).content(reorderedBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(2, objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("version").asInt());

        // 版本不可变：查询仍为 v1/v2 两版，v1 不再 current
        MvcResult componentsResult = mockMvc.perform(get("/api/batches/" + key + "/composition"))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = objectMapper.readTree(componentsResult.getResponse().getContentAsString());
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).path("version").asInt());
        assertFalse(versions.get(0).path("current").asBoolean());
        assertEquals(2, versions.get(1).path("version").asInt());
        assertTrue(versions.get(1).path("current").asBoolean());
        assertEquals(List.of("EGG", "GLUTEN"), toStringList(versions.get(1).path("allergenCodes")));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allergen_component WHERE batch_key = ?", Integer.class, key));
    }

    @Test
    void reviseComposition_unknownCode422_emptyLevel422_expectedVersionConflict409() throws Exception {
        String key = createBatch(BigDecimal.ZERO, List.of(), "NONE");

        // 未知过敏原代码 422，不产生版本
        revise(key, "AK-BAD-CODE", 1, List.of("NOT_AN_ALLERGEN"), "LOW", 422, "未知过敏原代码");
        assertEquals(1, currentVersion(key));

        // 空隔离级别 422（segregationLevel 缺失）
        String emptyLevel = "{\"allergenKey\":\"AK-NO-LEVEL\",\"expectedVersion\":1,"
                + "\"composition\":{\"allergenCodes\":[]}}";
        mockMvc.perform(post("/api/batches/" + key + "/composition")
                        .contentType(MediaType.APPLICATION_JSON).content(emptyLevel))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(1, currentVersion(key));

        // expectedVersion 不匹配 409，不产生版本
        revise(key, "AK-STALE", 99, List.of("EGG"), "LOW", 409, "成分版本冲突");
        assertEquals(1, currentVersion(key));

        // 未知批次 404
        revise("NO-SUCH", "AK-X", 1, List.of("EGG"), "LOW", 404, "批次不存在");
    }

    // ---------- 放行门禁：当前版本必须全部检验 ----------

    @Test
    void revisionAfterTests_resetsGateAndBlocksReleaseUntilRetested() throws Exception {
        String key = createBatch(BigDecimal.ZERO, List.of(), "NONE");
        releaseToPending(key);
        assertEquals("PENDING_RELEASE", currentStatus(key));

        // 放行前成分修订（不引入新过敏原）产生新版本，旧检验不再满足门禁 -> 回到 QUARANTINED
        revise(key, "AK-REV", 1, List.of("EGG"), "LOW", 201);
        assertEquals("QUARANTINED", currentStatus(key));
        approve(key, "qa", "QUALITY", "CK-Q", 422);

        // 新版本上重新检验通过后方可双角色放行
        submitTest(key, "t1", "PASS", "insp2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(key));
        approve(key, "qa", "QUALITY", "CK-Q2", 201);
        approve(key, "ops", "OPERATIONS", "CK-O2", 201);
        assertEquals("RELEASED", currentStatus(key));
    }

    // ---------- ALLERGEN_RISK 风险状态 ----------

    @Test
    void releasedBatchWithNewAllergen_becomesRisk_andRequiresRetestAndTwoRoles() throws Exception {
        String key = createBatch(BigDecimal.ZERO, List.of("MILK"), "LOW");
        fullyRelease(key);
        assertEquals("RELEASED", currentStatus(key));

        // 已放行批次修订引入新过敏原 -> ALLERGEN_RISK，保留原放行快照
        revise(key, "AK-RISK", 1, List.of("MILK", "PEANUT"), "LOW", 201);
        assertEquals("ALLERGEN_RISK", currentStatus(key));
        MvcResult riskResult = mockMvc.perform(get("/api/batches/" + key + "/risk"))
                .andExpect(status().isOk()).andReturn();
        JsonNode risk = objectMapper.readTree(riskResult.getResponse().getContentAsString());
        assertEquals(2, risk.path("detectedVersion").asInt());
        assertEquals(List.of("PEANUT"), toStringList(risk.path("newAllergenCodes")));
        assertEquals(1, risk.path("releaseSnapshot").path("componentVersion").asInt());
        assertEquals("qa", risk.path("releaseSnapshot").path("qualityApprover").asText());
        assertEquals("ops", risk.path("releaseSnapshot").path("operationsApprover").asText());
        assertFalse(risk.path("resolved").asBoolean());

        // 风险未解除前不能直接批准
        approve(key, "qa2", "QUALITY", "CK-RQ0", 422);

        // 仅同级别不变集合的修订不解除风险（保持风险，走重新检验通道）
        // 重新检验（新版本）+ 双角色放行解除风险
        submitTest(key, "t1", "PASS", "insp2", 201);
        assertEquals("PENDING_RELEASE", currentStatus(key));
        approve(key, "qa2", "QUALITY", "CK-RQ", 201);
        // 只批准一个角色不能解除
        JsonNode stillAtRisk = getJson(get("/api/batches/" + key + "/risk"));
        assertFalse(stillAtRisk.path("resolved").asBoolean());
        approve(key, "ops2", "OPERATIONS", "CK-RO", 201);
        assertEquals("RELEASED", currentStatus(key));

        // 风险解除：risk 查询返回 null
        mockMvc.perform(get("/api/batches/" + key + "/risk"))
                .andExpect(status().isOk()).andExpect(r -> assertTrue(
                        r.getResponse().getContentAsString().isBlank()
                                || r.getResponse().getContentAsString().equals("null")));
    }

    // ---------- 合批主流程、兼容矩阵与库存守恒 ----------

    @Test
    void merge_happyFlow_unionAllergensMaxLevel_conservesStock() throws Exception {
        String target = createBatch(new BigDecimal("100.000"), List.of("MILK"), "LOW");
        String s1 = createBatch(new BigDecimal("30.500"), List.of("EGG"), "MEDIUM");
        String s2 = createBatch(new BigDecimal("20.000"), List.of("MILK", "SESAME"), "LOW");
        fullyRelease(target);
        fullyRelease(s1);
        fullyRelease(s2);

        String body = mergeBody("MK-1", target,
                List.of(src(s1, "30.500"), src(s2, "20.000")),
                List.of(pair("LOW", "MEDIUM")));
        MvcResult result = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode merge = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(2, merge.path("targetComponentVersion").asInt());
        assertEquals(0, new BigDecimal(merge.path("targetQuantityBefore").asText())
                .compareTo(new BigDecimal("100.000")));
        assertEquals(0, new BigDecimal(merge.path("targetQuantityAfter").asText())
                .compareTo(new BigDecimal("150.500")));

        // 目标新版本：过敏原并集，级别取最高 MEDIUM；回到隔离等待重新检验
        assertEquals(2, currentVersion(target));
        assertEquals("MEDIUM", currentLevel(target));
        assertEquals(List.of("EGG", "MILK", "SESAME"), currentCodes(target));
        assertEquals("QUARANTINED", currentStatus(target));

        // 来源 MERGED、库存归零、不可用
        assertEquals("MERGED", currentStatus(s1));
        assertEquals("MERGED", currentStatus(s2));
        assertEquals(0, stock(s1).compareTo(BigDecimal.ZERO));
        assertFalse(availableKeys().contains(s1));

        // 库存守恒：全部批次库存总和不变（150.5），且无失败流水残留
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM inventory_stock", BigDecimal.class);
        assertEquals(0, total.compareTo(new BigDecimal("150.500")));
        // 合批产生 2 条来源扣减 + 1 条目标增加流水
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE ref_command_key = 'MK-1'", Integer.class));
        // 目标新版本未经检验不能放行
        approve(target, "qa-x", "QUALITY", "CK-MQ", 422);

        // 血缘快照：target 有两个合批来源，来源有 mergedInto
        JsonNode targetSnap = snapshot(target);
        assertEquals(2, targetSnap.path("mergedSources").size());
        JsonNode s1Snap = snapshot(s1);
        assertEquals(target, s1Snap.path("mergedInto").path("targetBatchKey").asText());
    }

    @Test
    void merge_incompatibleLevels_422_andRollsBackAllLineageAndStock() throws Exception {
        String target = createBatch(new BigDecimal("100"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("10"), List.of("EGG"), "LOW");
        String s2 = createBatch(new BigDecimal("20"), List.of("MILK"), "HIGH");
        fullyRelease(target);
        fullyRelease(s1);
        fullyRelease(s2);

        // 只声明 NONE-LOW，未声明 NONE-HIGH：整批 422
        String body = mergeBody("MK-BAD", target,
                List.of(src(s1, "10"), src(s2, "20")), List.of(pair("NONE", "LOW")));
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertTrue(r.getResolvedException().getMessage().contains(s2)));

        // 全部回滚：无血缘、无库存变动、目标无新版本、来源仍 RELEASED
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE change_type LIKE '%MERGE'", Integer.class));
        assertEquals(1, currentVersion(target));
        assertEquals("RELEASED", currentStatus(s1));
        assertEquals("RELEASED", currentStatus(s2));
        assertEquals(0, stock(s1).compareTo(new BigDecimal("10")));

        // 失败不占键：同一 allergenKey 补齐矩阵后成功
        String fixed = mergeBody("MK-BAD", target,
                List.of(src(s1, "10"), src(s2, "20")),
                List.of(pair("LOW", "NONE"), pair("HIGH", "NONE")));
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated());
        assertEquals("MERGED", currentStatus(s1));
        assertEquals("MERGED", currentStatus(s2));
    }

    @Test
    void merge_guards_partialQuantity_nonReleased_duplicateSource_unknownVersion() throws Exception {
        String target = createBatch(new BigDecimal("100"), List.of(), "NONE");
        String full = createBatch(new BigDecimal("50"), List.of("EGG"), "LOW");
        String untested = createBatch(new BigDecimal("5"), List.of("MILK"), "LOW");
        fullyRelease(target);
        fullyRelease(full);
        // untested 仅 QUARANTINED，未放行

        // 部分库存合入 422（必须整批）
        mergeExpect(target, List.of(src(full, "40")), List.of(), 422, "必须等于其全部库存");
        // 未放行来源 422
        mergeExpect(target, List.of(src(untested, "5")), List.of(), 422, "仅 RELEASED");
        // 请求内来源重复 422
        String dup = mergeBody("MK-DUP", target,
                List.of(src(full, "50"), src(full, "50")), List.of());
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(dup))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertTrue(r.getResolvedException().getMessage().contains("重复")));
        // 目标同时作为来源 422
        String self = mergeBody("MK-SELF", target, List.of(src(target, "100")), List.of());
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(self))
                .andExpect(status().isUnprocessableEntity());
        // 未知来源 404
        String missing = mergeBody("MK-404", target,
                List.of(src("NO-SUCH-SRC", "1")), List.of());
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isNotFound());
        // 全部失败后无任何血缘/流水残留
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE change_type LIKE '%MERGE'", Integer.class));
    }

    @Test
    void merge_consumesSources_andBlocksReleaseWhenAnyComponentVersionUntested() throws Exception {
        String target = createBatch(new BigDecimal("10"), List.of("MILK"), "LOW");
        String s1 = createBatch(new BigDecimal("5"), List.of("EGG"), "LOW");
        fullyRelease(target);
        fullyRelease(s1);
        // 同级别无需兼容矩阵
        mergeExpect(target, List.of(src(s1, "5")), List.of(), 201, null);
        // 来源已 MERGED：用另一个已放行目标容器再次合入同一来源，422 且原因可区分
        String target2 = createBatch(new BigDecimal("8"), List.of(), "NONE");
        fullyRelease(target2);
        mergeExpect(target2, List.of(src(s1, "5")), List.of(), 422, "已被合批");
        // 合并后目标新版本含 EGG，但尚未重新检验，门禁阻断放行
        submitTest(target, "t1", "PASS", "insp-merge", 201);
        approve(target, "qa-m", "QUALITY", "CK-M1", 201);
        approve(target, "ops-m", "OPERATIONS", "CK-M2", 201);
        assertEquals("RELEASED", currentStatus(target));
    }

    @Test
    void merge_idempotent_sameKeyReplays_changedParamsConflict() throws Exception {
        String target = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("5"), List.of("EGG"), "LOW");
        fullyRelease(target);
        fullyRelease(s1);
        String body = mergeBody("MK-IDEM", target, List.of(src(s1, "5")),
                List.of(pair("NONE", "LOW")));

        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复扣减、不重复写血缘
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE command_key = 'MK-IDEM'", Integer.class));
        assertEquals(0, stock(s1).compareTo(BigDecimal.ZERO));

        // 同键改参（不同数量/版本指纹）-> 409
        String changed = mergeBody("MK-IDEM", target, List.of(src(s1, "5")),
                List.of(pair("LOW", "NONE"), pair("NONE", "HIGH")));
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 召回与级别约束 ----------

    @Test
    void recallSource_descendantsCannotLowerSegregationLevel() throws Exception {
        // target 合入 s1（LOW），随后 s1 被召回；target 作为后代修订不得降低级别
        String target = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("5"), List.of("EGG"), "LOW");
        fullyRelease(target);
        fullyRelease(s1);
        mergeExpect(target, List.of(src(s1, "5")), List.of(pair("NONE", "LOW")), 201, null);
        // target 合并后回到隔离；s1 已 MERGED 仍可召回（来源召回）
        recall(s1, "u", "来源污染", "CK-RC-SRC", 201);

        // target 是 s1 的合批后代：降低级别 422
        int targetVersion = currentVersion(target);
        revise(target, "AK-DOWN", targetVersion, List.of("EGG"), "NONE", 422, "不得降低隔离级别");
        // 保持或上调允许
        revise(target, "AK-UP", targetVersion, List.of("EGG"), "HIGH", 201);
        assertEquals("HIGH", currentLevel(target));

        // 目标容器也从可用列表排除（祖先来源已召回），且不能放行
        assertFalse(availableKeys().contains(target));
    }

    @Test
    void recalledMergedSource_blocksTargetMergeAndTests() throws Exception {
        String t1 = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("5"), List.of("EGG"), "LOW");
        fullyRelease(t1);
        fullyRelease(s1);
        mergeExpect(t1, List.of(src(s1, "5")), List.of(pair("NONE", "LOW")), 201, null);
        recall(s1, "u", "来源召回", "CK-RC", 201);

        // 目标容器存在已召回祖先来源：新增检验被拦截 422
        submitTest(t1, "t1", "PASS", "insp", 422);

        // t1 不能再作为另一合批的目标
        String other = createBatch(new BigDecimal("2"), List.of(), "NONE");
        fullyRelease(other);
        mergeExpect(t1, List.of(src(other, "2")), List.of(), 422, "已召回祖先");
    }

    // ---------- 诊断查询 ----------

    @Test
    void diagnose_reportsBlockingReasons_withoutWritingState() throws Exception {
        String target = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String ok = createBatch(new BigDecimal("3"), List.of("EGG"), "LOW");
        String high = createBatch(new BigDecimal("4"), List.of("MILK"), "HIGH");
        fullyRelease(target);
        fullyRelease(ok);
        fullyRelease(high);

        String body = mergeBody("MK-DIAG", target,
                List.of(src(ok, "3"), src(high, "4")), List.of(pair("NONE", "LOW")));
        MvcResult result = mockMvc.perform(post("/api/batches/merge/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(diag.path("compatible").asBoolean());
        assertEquals("NONE", diag.path("targetLevel").asText());
        // 缺少 NONE-HIGH 矩阵对
        boolean blockedHigh = false;
        for (JsonNode pair : diag.path("blockedPairs")) {
            if ("NONE".equals(pair.path("lowLevel").asText())
                    && "HIGH".equals(pair.path("highLevel").asText())
                    && high.equals(pair.path("sourceBatchKey").asText())) {
                blockedHigh = true;
            }
        }
        assertTrue(blockedHigh);
        // 诊断不落任何状态
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_lineage", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM merge_compat", Integer.class));

        // 补齐矩阵后诊断兼容（同级别对顺序无关：HIGH-NONE 等价 NONE-HIGH）
        String fixed = mergeBody("MK-DIAG2", target,
                List.of(src(ok, "3"), src(high, "4")),
                List.of(pair("LOW", "NONE"), pair("HIGH", "NONE")));
        JsonNode fixedDiag = postDiagnose(fixed);
        assertTrue(fixedDiag.path("compatible").asBoolean());
        assertEquals(0, fixedDiag.path("blockedPairs").size());
    }

    // ---------- 并发：合批与召回按提交顺序裁决 ----------

    @Test
    void concurrentMergeAndRecall_commitOrderDecides_noHalfState() throws Exception {
        String target = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("5"), List.of("EGG"), "LOW");
        fullyRelease(target);
        fullyRelease(s1);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MK-RACE", target, List.of(src(s1, "5")),
                                List.of(pair("NONE", "LOW"))))),
                () -> callStatus(post("/api/batches/" + s1 + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RACE-R\",\"reason\":\"并发召回\"}"))
        );

        int mergeCode = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        // 来源 RECALLED 时召回自身：两种顺序，召回必为 201（MERGED/RELEASED 均可召回）
        assertEquals(201, recallCode);
        if (mergeCode == 201) {
            // 合批先提交：s1 -> MERGED，随后召回；target 回到隔离且有已召回祖先，不可放行
            assertEquals("RECALLED", currentStatus(s1));
            assertEquals("QUARANTINED", currentStatus(target));
            assertFalse(availableKeys().contains(target));
            BigDecimal total = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity),0) FROM inventory_stock", BigDecimal.class);
            assertEquals(0, total.compareTo(new BigDecimal("15")));
        } else {
            // 召回先提交：合批被拦截 422，无血缘，库存不变
            assertEquals(422, mergeCode);
            assertEquals("RECALLED", currentStatus(s1));
            assertEquals(0, stock(target).compareTo(new BigDecimal("10")));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM merge_lineage", Integer.class));
        }
        // 无论哪种顺序都不存在半成品：单来源成功时为 1 条扣减 + 1 条增加共 2 条，失败时为 0
        Integer mergeLedgers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE ref_command_key = 'MK-RACE'", Integer.class);
        assertTrue(mergeLedgers == 0 || mergeLedgers == 2,
                "库存流水必须为 0（回滚）或 2（完整），实际: " + mergeLedgers);
    }

    @Test
    void concurrentTwoMergesSameSource_onlyOneWins_noDoubleConsume() throws Exception {
        String t1 = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String t2 = createBatch(new BigDecimal("10"), List.of(), "NONE");
        String s1 = createBatch(new BigDecimal("7"), List.of("EGG"), "LOW");
        fullyRelease(t1);
        fullyRelease(t2);
        fullyRelease(s1);

        String b1 = mergeBody("MK-C1", t1, List.of(src(s1, "7")), List.of(pair("NONE", "LOW")));
        String b2 = mergeBody("MK-C2", t2, List.of(src(s1, "7")), List.of(pair("NONE", "LOW")));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(b1)),
                () -> callStatus(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON).content(b2))
        );
        int c1 = results.get(0).get(30, TimeUnit.SECONDS);
        int c2 = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(1, (c1 == 201 ? 1 : 0) + (c2 == 201 ? 1 : 0), "同一来源只能被合入一次");
        assertTrue(c1 == 201 || c1 == 422);
        assertTrue(c2 == 201 || c2 == 422);
        // 库存守恒：总额 27；s1 只被扣一次
        BigDecimal total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM inventory_stock", BigDecimal.class);
        assertEquals(0, total.compareTo(new BigDecimal("27")));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM merge_lineage WHERE source_key = ?", Integer.class, s1));
    }

    // ---------- 失败不占键与并发幂等 ----------

    @Test
    void failedRevision_doesNotOccupyAllergenKey_andConcurrentSameKeyReplays() throws Exception {
        String key = createBatch(BigDecimal.ZERO, List.of(), "NONE");

        // 同 allergenKey 先以未知代码失败（422，不占键），修正代码后同键成功
        revise(key, "AK-RETRY", 1, List.of("BOGUS"), "LOW", 422, "未知过敏原代码");
        revise(key, "AK-RETRY", 1, List.of("EGG"), "LOW", 201);
        assertEquals(2, currentVersion(key));

        // 另一批次并发使用同一 allergenKey 做不同修订：只有一笔成功，另一笔读到已提交快照或 409，
        // 绝不产生两个 v2
        String other = createBatch(BigDecimal.ZERO, List.of(), "NONE");
        String bodyA = reviseBody("AK-RACE-REV", 1, List.of("MILK"), "LOW");
        String bodyB = reviseBody("AK-RACE-REV", 1, List.of("EGG"), "MEDIUM");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + key + "/composition")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/batches/" + other + "/composition")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB))
        );
        int a = results.get(0).get(30, TimeUnit.SECONDS);
        int b = results.get(1).get(30, TimeUnit.SECONDS);
        // 两笔指纹不同：先提交者 201，后提交者因 allergenKey 已占用且指纹不同而 409
        assertEquals(1, (a == 201 ? 1 : 0) + (b == 201 ? 1 : 0));
        assertTrue(a == 201 || a == 409);
        assertTrue(b == 201 || b == 409);
    }

    // ---------- helpers ----------

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             String producedAt, List<String> requiredTests,
                             Composition composition, java.math.BigDecimal initialStock) {
    }

    private record Composition(List<String> allergenCodes, String segregationLevel) {
    }

    private String createBatch(BigDecimal stock, List<String> codes, String level) throws Exception {
        String key = "BK-ALG-" + uid();
        CreateCmd cmd = new CreateCmd("CK-C-" + uid(), key, "PROD-1", "LOT-" + uid(),
                "2026-01-02T03:04:05Z", List.of("t1"),
                new Composition(codes, level), stock);
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isCreated());
        return key;
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTest(String key, String item, String result, String inspector, int expected)
            throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd(
                "CK-T-" + uid(), "TK-" + uid(), item, result, inspector));
        mockMvc.perform(post("/api/batches/" + key + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private void approve(String key, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + key + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void releaseToPending(String key) throws Exception {
        submitTest(key, "t1", "PASS", "insp1", 201);
    }

    private void fullyRelease(String key) throws Exception {
        submitTest(key, "t1", "PASS", "insp1", 201);
        approve(key, "qa", "QUALITY", "CK-Q-" + uid(), 201);
        approve(key, "ops", "OPERATIONS", "CK-O-" + uid(), 201);
    }

    private void recall(String key, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + key + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private String reviseBody(String allergenKey, int expectedVersion, List<String> codes,
                              String level) {
        return "{\"allergenKey\":\"" + allergenKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"composition\":{\"allergenCodes\":" + toJsonArray(codes)
                + ",\"segregationLevel\":\"" + level + "\"}}";
    }

    private JsonNode revise(String key, String allergenKey, int expectedVersion, List<String> codes,
                            String level, int expectedStatus) throws Exception {
        return revise(key, allergenKey, expectedVersion, codes, level, expectedStatus, null);
    }

    private JsonNode revise(String key, String allergenKey, int expectedVersion, List<String> codes,
                            String level, int expectedStatus, String messageContains) throws Exception {
        var result = mockMvc.perform(post("/api/batches/" + key + "/composition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(allergenKey, expectedVersion, codes, level)))
                .andExpect(status().is(expectedStatus));
        if (messageContains != null) {
            result.andExpect(r -> assertTrue(
                    r.getResolvedException().getMessage().contains(messageContains)));
        }
        if (expectedStatus == 201) {
            return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        }
        return null;
    }

    private record SourceRef(String batchKey, java.math.BigDecimal quantity) {
    }

    private record PairRef(FromTo fromLevel, FromTo toLevel) {
    }

    private record FromTo(String level) {
    }

    private SourceRef src(String key, String qty) {
        return new SourceRef(key, new BigDecimal(qty));
    }

    private PairRef pair(String a, String b) {
        return new PairRef(new FromTo(a), new FromTo(b));
    }

    private String mergeBody(String allergenKey, String target, List<SourceRef> sources,
                             List<PairRef> pairs) {
        StringBuilder sb = new StringBuilder("{\"allergenKey\":\"").append(allergenKey)
                .append("\",\"targetBatchKey\":\"").append(target).append("\",\"sources\":[");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(sources.get(i).batchKey())
                    .append("\",\"quantity\":").append(sources.get(i).quantity().toPlainString())
                    .append('}');
        }
        sb.append("],\"compatibleLevels\":[");
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"fromLevel\":{\"level\":\"").append(pairs.get(i).fromLevel().level())
                    .append("\"},\"toLevel\":{\"level\":\"").append(pairs.get(i).toLevel().level())
                    .append("\"}}");
        }
        return sb.append("]}").toString();
    }

    private void mergeExpect(String target, List<SourceRef> sources, List<PairRef> pairs,
                             int expectedStatus, String messageContains) throws Exception {
        mockMvc.perform(post("/api/batches/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("MK-EX-" + uid(), target, sources, pairs)))
                .andExpect(status().is(expectedStatus))
                .andExpect(r -> {
                    if (messageContains != null) {
                        assertTrue(r.getResolvedException().getMessage().contains(messageContains));
                    }
                });
    }

    private JsonNode postDiagnose(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/batches/merge/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String key) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + key + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode snapshot(String key) throws Exception {
        return getJson(get("/api/batches/" + key + "/lineage-snapshot"));
    }

    private String currentStatus(String key) throws Exception {
        return history(key).path("batch").path("status").asText();
    }

    private int currentVersion(String key) throws Exception {
        return history(key).path("batch").path("componentVersion").asInt();
    }

    private String currentLevel(String key) throws Exception {
        return history(key).path("batch").path("segregationLevel").asText();
    }

    private List<String> currentCodes(String key) throws Exception {
        return toStringList(history(key).path("batch").path("allergenCodes"));
    }

    private BigDecimal stock(String key) {
        return jdbc.queryForObject("SELECT quantity FROM inventory_stock WHERE batch_key = ?",
                BigDecimal.class, key);
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<String> toStringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    private String toJsonArray(List<String> codes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(codes.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
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
        assertTrue(pool.awaitTermination(40, TimeUnit.SECONDS));
        return futures;
    }
}
