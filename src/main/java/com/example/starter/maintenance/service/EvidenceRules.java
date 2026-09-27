package com.example.starter.maintenance.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest.CheckResultInput;
import com.example.starter.maintenance.domain.TemplateItem;

/**
 * 证据包纯业务规则（无 IO、无 Spring 依赖，便于单元测试）。
 * 一次更新按“完整最终包”裁决：先合并旧版本结果与本次更新，再统一校验齐备性。
 */
public final class EvidenceRules {

    /** 材料批次编码：MAT- 前缀加 4-16 位大写字母或数字。 */
    public static final Pattern BATCH_CODE_PATTERN = Pattern.compile("MAT-[A-Z0-9]{4,16}");

    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_FAIL = "FAIL";

    private EvidenceRules() {
    }

    /** 材料批次摘要格式是否合法。 */
    public static boolean isValidBatchCode(String batchCode) {
        return batchCode != null && BATCH_CODE_PATTERN.matcher(batchCode).matches();
    }

    /**
     * 最终包中的检查项结果（按模板 ordinal 排序后输出）。
     *
     * @param itemCode        检查项编码
     * @param ordinal         模板内规范序号
     * @param pass            是否 PASS
     * @param remark          备注（null 表示无备注）
     * @param packageVersion  该结果最近一次更新所在的证据包版本
     * @param submittedBy     该结果的提交人
     * @param createdAt       该结果提交时刻（UTC）
     */
    public record FinalResult(
            String itemCode,
            int ordinal,
            boolean pass,
            String remark,
            int packageVersion,
            String submittedBy,
            Instant createdAt) {
    }

    /**
     * 最终包齐备性诊断。
     *
     * @param missingItems  尚缺结果的必检项（按 ordinal 升序）
     * @param failedItems   最终结果为 FAIL 的必检项（按 ordinal 升序）
     * @param complete      每项恰有一个结果、无 FAIL 且至少一条材料摘要
     */
    public record Evaluation(List<String> missingItems, List<String> failedItems, boolean complete) {
    }

    /** 同一次更新中重复出现的检查项编码（按出现顺序去重）。 */
    public static List<String> findDuplicateItemCodes(List<CheckResultInput> updates) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        if (updates != null) {
            for (CheckResultInput update : updates) {
                if (!seen.add(update.itemCode())) {
                    duplicates.add(update.itemCode());
                }
            }
        }
        return new ArrayList<>(duplicates);
    }

    /** 结果取值是否合法（恰为 PASS 或 FAIL）。 */
    public static boolean isValidResultToken(String result) {
        return RESULT_PASS.equals(result) || RESULT_FAIL.equals(result);
    }

    /**
     * 将本次更新合并到当前最终结果：同一检查项以本次值覆盖旧值；
     * 输出严格按模板规范排序（ordinal 升序）。
     */
    public static List<FinalResult> mergeResults(List<TemplateItem> templateItems,
                                                 Map<String, FinalResult> currentByItem,
                                                 List<CheckResultInput> updates,
                                                 int newPackageVersion,
                                                 String submittedBy,
                                                 Instant now) {
        Map<String, FinalResult> merged = new LinkedHashMap<>(currentByItem);
        if (updates != null) {
            for (CheckResultInput update : updates) {
                TemplateItem item = templateItems.stream()
                        .filter(candidate -> candidate.itemCode().equals(update.itemCode()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("检查项不属于当前模板："
                                + update.itemCode()));
                merged.put(update.itemCode(), new FinalResult(
                        item.itemCode(), item.ordinal(), RESULT_PASS.equals(update.result()),
                        update.remark(), newPackageVersion, submittedBy, now));
            }
        }
        List<FinalResult> ordered = new ArrayList<>();
        for (TemplateItem item : templateItems) {
            FinalResult result = merged.get(item.itemCode());
            if (result != null) {
                ordered.add(result);
            }
        }
        return ordered;
    }

    /**
     * 评估完整最终包：缺失项、FAIL 项及齐备性。
     * 每个必检项恰有一个结果（由合并保证唯一键）、全部 PASS、至少一条材料摘要才齐备。
     */
    public static Evaluation evaluate(List<TemplateItem> templateItems,
                                      List<FinalResult> finalResults,
                                      int materialCount) {
        Map<String, FinalResult> byCode = new LinkedHashMap<>();
        for (FinalResult result : finalResults) {
            byCode.put(result.itemCode(), result);
        }
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (TemplateItem item : templateItems) {
            FinalResult result = byCode.get(item.itemCode());
            if (result == null) {
                missing.add(item.itemCode());
            } else if (!result.pass()) {
                failed.add(item.itemCode());
            }
        }
        boolean complete = missing.isEmpty() && failed.isEmpty() && materialCount > 0;
        return new Evaluation(List.copyOf(missing), List.copyOf(failed), complete);
    }
}
