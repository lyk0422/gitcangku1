package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 保养周期视图：当前周期明细、最新证据包与完工门禁诊断。读取不改变任何状态。
 *
 * @param cycleNo                       周期号，设备内从 1 递增
 * @param status                        周期状态：OPEN / COMPLETED
 * @param periodMinutes                 该周期要求工时（分钟，创建时固化）
 * @param startAnchorSampledAt          周期起点锚点时刻（UTC）；首周期为 null
 * @param startAnchorCumulativeMinutes  周期起点锚点工时（分钟）；首周期为 0
 * @param currentEvidenceVersion        当前最新证据包版本；0 表示尚无证据包
 * @param currentTemplateVersion        最新证据包固化时的模板版本；无证据包时为 null
 * @param latestTemplateVersion         设备当前模板版本；模板未建立时为 null
 * @param completedAt                   完工时刻（UTC）；未完工为 null
 * @param endAnchorSampledAt            完工锚点采样时刻（UTC 快照）；未完工为 null
 * @param endAnchorCumulativeMinutes    完工锚点工时快照（分钟）；未完工为 null
 * @param maintenanceId                 完工保养记录标识；未完工为 null
 * @param readiness                     未完工周期的门禁诊断；已完工周期为 null
 */
public record CycleResponse(
        int cycleNo,
        String status,
        long periodMinutes,
        Instant startAnchorSampledAt,
        long startAnchorCumulativeMinutes,
        int currentEvidenceVersion,
        Integer currentTemplateVersion,
        Integer latestTemplateVersion,
        Instant completedAt,
        Instant endAnchorSampledAt,
        Long endAnchorCumulativeMinutes,
        Long maintenanceId,
        ReadinessView readiness) {

    /**
     * 完工门禁诊断：基于当前最新证据包与设备状态计算，仅读取，不落库。
     *
     * @param requiredItemCount        当前模板必检项数量（要求值）
     * @param passedRequiredItemCount  最新证据包中结果为 PASS 的必检项数量（实际值）
     * @param missingRequiredItems     缺失（无结果）或结果不为 PASS 的必检项编码（含 FAIL）
     * @param failedItems              最新证据包中结果为 FAIL 的检查项编码（含非必检项）
     * @param materialSummaryCount     周期内已提交材料批次摘要数量（实际值，要求至少 1）
     * @param equipmentStatus          设备当前判定状态：OK / DUE / OVERDUE
     * @param overdue                  设备当前是否已 OVERDUE（OVERDUE 时禁止完工）
     * @param readyToComplete          全部门禁是否满足（无缺失/失败、至少一个材料、未逾期、版本仍最新）
     * @param reasons                  不满足门禁的原因列表；满足时为空列表
     */
    public record ReadinessView(
            int requiredItemCount,
            int passedRequiredItemCount,
            List<String> missingRequiredItems,
            List<String> failedItems,
            int materialSummaryCount,
            String equipmentStatus,
            boolean overdue,
            boolean readyToComplete,
            List<String> reasons) {
    }
}
