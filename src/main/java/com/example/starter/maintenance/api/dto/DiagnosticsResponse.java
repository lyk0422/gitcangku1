package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 当前周期证据包诊断视图：列出缺失必检项、失败项与已撤销材料，并给出数量实际值/要求值。
 *
 * @param equipmentId              设备标识
 * @param cycleNo                  当前保养周期序号
 * @param currentPackageVersion    当前周期最新证据包版本号，无证据包时为 null
 * @param templateId               设备保养模板标识，未分配为 null
 * @param currentTemplateVersion   模板当前版本号，未分配模板为 null
 * @param packageTemplateVersion   最新证据包提交时的模板版本号，无证据包为 null
 * @param status                   设备保养状态：OK / DUE / OVERDUE
 * @param runMinutes               本轮运行分钟（实际值）
 * @param maintenancePeriodMinutes 保养周期（分钟，要求值）
 * @param minutesUntilDue          距到期差额 = 保养周期 - 本轮运行分钟（负数表示已超出）
 * @param requiredItemCount        当前模板必检项数量（要求值）
 * @param passedRequiredItemCount  证据包中已 PASS 的必检项数量（实际值）
 * @param missingRequiredItems     缺少 PASS 结果的必检项编码（规范排序）
 * @param failedItems              结果为 FAIL 的检查项编码（规范排序）
 * @param revokedMaterials         证据包引用但已撤销的材料批次编码（升序）
 * @param materialCount            证据包材料摘要条数（要求至少 1）
 * @param readyForCompletion       证据包是否齐备可完工（无缺失/失败/撤销且未 OVERDUE）
 */
public record DiagnosticsResponse(
        String equipmentId,
        long cycleNo,
        Integer currentPackageVersion,
        String templateId,
        Integer currentTemplateVersion,
        Integer packageTemplateVersion,
        String status,
        long runMinutes,
        long maintenancePeriodMinutes,
        long minutesUntilDue,
        int requiredItemCount,
        int passedRequiredItemCount,
        List<String> missingRequiredItems,
        List<String> failedItems,
        List<String> revokedMaterials,
        int materialCount,
        boolean readyForCompletion) {
}
