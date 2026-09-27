package com.example.starter.disclosure.dto;

/**
 * 披露包冻结摘要视图：建包时固化的生成摘要（结构化 JSON），
 * 包含案件、版本、截至时刻与逐件链版本/位置/封签快照，之后不再改写。
 *
 * @param packageKey     包指纹
 * @param caseKey        所属案件键
 * @param packageVersion 包版本
 * @param basedOnVersion 修订基于的版本；null 首版
 * @param cutoffAt       披露截至时刻（UTC，ISO 字符串）
 * @param status         生成时包状态（固定 GENERATED）
 * @param actorId        建包操作者
 * @param evidenceCount  包内证物数量
 * @param generatedAt    生成时间（Asia/Shanghai，ISO 字符串）
 * @param frozenSummary  逐件冻结摘要
 */
public record PackageSummaryView(
        String packageKey,
        String caseKey,
        int packageVersion,
        Integer basedOnVersion,
        String cutoffAt,
        String status,
        String actorId,
        int evidenceCount,
        String generatedAt,
        Object frozenSummary) {
}
