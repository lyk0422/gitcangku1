package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;

import java.time.Instant;

/**
 * 复检延期确认生效响应：确认在同一事务内追加不可变延期记录并整体顺延有效期。
 * validUntil 为顺延后的新有效期；expired/remainingMinutes 相对服务端当前时刻；
 * effectiveAt 为 UTC instant。批次状态与既有检验、批准记录不变。
 */
public record ConfirmExtensionResponse(
        String extensionKey,
        String batchKey,
        String inspectorId,
        String reinspectionConclusion,
        int extendMinutes,
        String confirmerId,
        ApprovalRole confirmerRole,
        String status,
        Instant validUntil,
        boolean expired,
        long remainingMinutes,
        Instant effectiveAt
) {
}
