package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 证据包版本视图（完整最终包快照）。读取不改变状态。
 *
 * @param equipmentId      所属设备标识
 * @param cycleNo          保养周期序号
 * @param packageVersion   证据包版本号
 * @param templateVersion  固化的模板版本
 * @param submittedBy      该版本提交人
 * @param createdAt        版本生成时刻（UTC）
 * @param complete         最终包是否齐备：每个必检项恰有一个结果、至少一条材料摘要
 * @param missingItems     诊断：尚缺结果的必检项编码（按 ordinal 升序）
 * @param failedItems      诊断：最终结果为 FAIL 的检查项编码（按 ordinal 升序）
 * @param checkResults     最终包全部检查项结果（按 ordinal 升序）
 * @param materials        最终包全部材料批次摘要（按进入版本、批次编码排序）
 */
public record EvidenceResponse(
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int templateVersion,
        String submittedBy,
        Instant createdAt,
        boolean complete,
        List<String> missingItems,
        List<String> failedItems,
        List<CheckResultView> checkResults,
        List<MaterialSummaryView> materials) {

    /**
     * 检查项结果视图（快照值）。
     *
     * @param ordinal         模板内规范序号
     * @param itemCode        检查项编码
     * @param result          PASS 或 FAIL
     * @param remark          备注（null 表示无备注）
     * @param packageVersion  该结果最近一次更新所在的证据包版本
     * @param submittedBy     该结果的提交人
     * @param createdAt       该结果提交时刻（UTC）
     */
    public record CheckResultView(
            int ordinal,
            String itemCode,
            String result,
            String remark,
            int packageVersion,
            String submittedBy,
            Instant createdAt) {
    }

    /**
     * 材料批次摘要视图（快照值）。
     *
     * @param batchCode      材料批次编码
     * @param quantity       使用数量
     * @param unit           数量单位
     * @param addedVersion   该摘要首次进入证据包的版本
     * @param submittedBy    摘要提交人
     * @param createdAt      摘要提交时刻（UTC）
     */
    public record MaterialSummaryView(
            String batchCode,
            long quantity,
            String unit,
            int addedVersion,
            String submittedBy,
            Instant createdAt) {
    }
}
