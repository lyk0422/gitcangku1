package com.example.starter.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest.CheckResultInput;
import com.example.starter.maintenance.domain.TemplateItem;

/**
 * 证据包规则纯单元测试：覆盖批次格式、同次重复检测、最终包合并排序与齐备性诊断。
 */
class EvidenceRulesTest {

    private final TemplateItem i0 = new TemplateItem("eq", 1, 0, "I1", "润滑");
    private final TemplateItem i1 = new TemplateItem("eq", 1, 1, "I2", "紧固");
    private final List<TemplateItem> items = List.of(i0, i1);
    private final Instant now = Instant.parse("2026-03-01T08:00:00Z");

    @Test
    void batchCode_formatEnforced() {
        assertTrue(EvidenceRules.isValidBatchCode("MAT-AB12"));
        assertTrue(EvidenceRules.isValidBatchCode("MAT-ABCD0123"));
        assertFalse(EvidenceRules.isValidBatchCode("MAT-ab12"));
        assertFalse(EvidenceRules.isValidBatchCode("MAT-AB1"));
        assertFalse(EvidenceRules.isValidBatchCode("MAT-AB123456789012345"));
        assertFalse(EvidenceRules.isValidBatchCode("MATERIAL-AB12"));
        assertFalse(EvidenceRules.isValidBatchCode(null));
    }

    @Test
    void resultTokens_validated() {
        assertTrue(EvidenceRules.isValidResultToken("PASS"));
        assertTrue(EvidenceRules.isValidResultToken("FAIL"));
        assertFalse(EvidenceRules.isValidResultToken("pass"));
        assertFalse(EvidenceRules.isValidResultToken(""));
    }

    @Test
    void duplicateItemCodes_detectedInOrder() {
        List<CheckResultInput> updates = List.of(
                new CheckResultInput("I1", "PASS", null),
                new CheckResultInput("I2", "FAIL", null),
                new CheckResultInput("I1", "FAIL", null),
                new CheckResultInput("I2", "PASS", null));
        assertEquals(List.of("I1", "I2"), EvidenceRules.findDuplicateItemCodes(updates));
        assertEquals(List.of(), EvidenceRules.findDuplicateItemCodes(List.of()));
        assertEquals(List.of(), EvidenceRules.findDuplicateItemCodes(null));
    }

    @Test
    void mergeResults_overwriteAndCanonicalOrder() {
        // 旧最终包：I1=PASS（版本 1）。本次把 I1 改 FAIL 并补 I2=PASS，最终包须按 ordinal 排序且每恰一项
        Map<String, EvidenceRules.FinalResult> current = Map.of(
                "I1", new EvidenceRules.FinalResult("I1", 0, true, null, 1, "alice", now));
        List<EvidenceRules.FinalResult> merged = EvidenceRules.mergeResults(
                items, current,
                List.of(new CheckResultInput("I2", "PASS", null),
                        new CheckResultInput("I1", "FAIL", "渗油")),
                2, "bob", now);
        assertEquals(2, merged.size());
        assertEquals("I1", merged.get(0).itemCode());
        assertFalse(merged.get(0).pass());
        assertEquals("渗油", merged.get(0).remark());
        assertEquals(2, merged.get(0).packageVersion());
        assertEquals("bob", merged.get(0).submittedBy());
        assertEquals("I2", merged.get(1).itemCode());
        assertTrue(merged.get(1).pass());
    }

    @Test
    void evaluate_missingFailedAndMaterialGate() {
        // 无任何结果、无材料：两项都缺失
        EvidenceRules.Evaluation empty = EvidenceRules.evaluate(items, List.of(), 0);
        assertEquals(List.of("I1", "I2"), empty.missingItems());
        assertEquals(List.of(), empty.failedItems());
        assertFalse(empty.complete());

        // I1 PASS、缺 I2、无材料：仍不齐备
        EvidenceRules.Evaluation partial = EvidenceRules.evaluate(items,
                List.of(new EvidenceRules.FinalResult("I1", 0, true, null, 1, "alice", now)), 0);
        assertEquals(List.of("I2"), partial.missingItems());
        assertFalse(partial.complete());

        // I1 FAIL、I2 PASS、有材料：缺失为空但 FAIL 列出，不齐备
        EvidenceRules.Evaluation failed = EvidenceRules.evaluate(items,
                List.of(new EvidenceRules.FinalResult("I1", 0, false, null, 1, "alice", now),
                        new EvidenceRules.FinalResult("I2", 1, true, null, 1, "alice", now)), 1);
        assertEquals(List.of(), failed.missingItems());
        assertEquals(List.of("I1"), failed.failedItems());
        assertFalse(failed.complete());

        // 全部 PASS 且有材料：齐备
        EvidenceRules.Evaluation ready = EvidenceRules.evaluate(items,
                List.of(new EvidenceRules.FinalResult("I1", 0, true, null, 1, "alice", now),
                        new EvidenceRules.FinalResult("I2", 1, true, null, 2, "bob", now)), 2);
        assertTrue(ready.complete());
    }
}
