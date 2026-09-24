package com.example.starter.consent.dto;

import java.util.List;

/**
 * 按主体查询快照列表的响应：只读汇总，不含记录明细。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param snapshots  该主体全部快照，按生成时刻升序
 */
public record ExportListResponse(String subjectKey, List<ExportSummaryView> snapshots) {

    /**
     * 单份快照的汇总视图。
     *
     * @param exportKey   导出标识
     * @param generatedAt 生成时刻（ISO-8601，UTC 偏移）
     * @param purposes    逐用途汇总（用途、固化代次、记录数、读取时状态）
     */
    public record ExportSummaryView(String exportKey, String generatedAt, List<PurposeSummary> purposes) {
    }

    /**
     * 单个用途的汇总。
     *
     * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
     * @param epoch       生成时刻该用途的有效授权代次
     * @param recordCount 生成时刻该用途该代次的记录数
     * @param status      读取时状态：CURRENT 与当前有效代次一致 / STALE 已撤回或已推进
     */
    public record PurposeSummary(com.example.starter.consent.Purpose purpose, int epoch, int recordCount,
                                 com.example.starter.consent.SnapshotStatus status) {
    }
}
