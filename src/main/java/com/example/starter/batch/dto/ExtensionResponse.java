package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 复检延期提交响应：请求进入 PENDING 待确认，尚未改变批次有效期；
 * 须由另一名不同于复检人的批准角色确认后才生效。submittedAt 为 UTC instant。
 */
public record ExtensionResponse(
        String extensionKey,
        String batchKey,
        String inspectorId,
        String reinspectionConclusion,
        int extendMinutes,
        String status,
        Instant submittedAt
) {
}
