package com.example.starter.maintenance.service;

import java.util.List;

import com.example.starter.maintenance.domain.EvidencePackage;

/**
 * 当前周期证据诊断结果（纯数据，由 {@link EvidenceRules} 计算）。
 *
 * @param latestPackage          当前周期最新证据包，无证据包为 null
 * @param currentTemplateVersion 模板当前版本号，未分配模板为 null
 * @param runMinutes             本轮运行分钟（实际值）
 * @param overdue                是否已 OVERDUE（runMinutes 达到保养周期 2 倍）
 * @param requiredItemCount      当前模板必检项数量（要求值）
 * @param passedRequiredItemCount 证据包中已 PASS 的必检项数量（实际值）
 * @param missingRequiredItems   缺少 PASS 结果的必检项编码（规范排序）
 * @param failedItems            结果为 FAIL 的检查项编码（规范排序）
 * @param revokedMaterials       证据包引用但已撤销的材料批次编码（升序）
 * @param materialCount          证据包材料摘要条数（要求至少 1）
 */
public record EvidenceDiagnostics(
        EvidencePackage latestPackage,
        Integer currentTemplateVersion,
        long runMinutes,
        boolean overdue,
        int requiredItemCount,
        int passedRequiredItemCount,
        List<String> missingRequiredItems,
        List<String> failedItems,
        List<String> revokedMaterials,
        int materialCount) {

    /** 证据包是否齐备可完工：已提交、必检项全部 PASS、无 FAIL、无已撤销材料、至少一个材料且未 OVERDUE。 */
    public boolean readyForCompletion() {
        return latestPackage != null
                && missingRequiredItems.isEmpty()
                && failedItems.isEmpty()
                && revokedMaterials.isEmpty()
                && materialCount >= 1
                && !overdue;
    }
}
