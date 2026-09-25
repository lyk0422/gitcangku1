package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;

import java.time.Instant;

/**
 * 已生效延期记录：追加写、不可变，仅在确认事务内产生。effectiveAt 为 UTC instant。
 */
public record ExtensionRecordResponse(
        String extensionKey,
        String batchKey,
        int extendMinutes,
        String inspectorId,
        String reinspectionConclusion,
        String confirmerId,
        ApprovalRole confirmerRole,
        Instant effectiveAt
) {
}
