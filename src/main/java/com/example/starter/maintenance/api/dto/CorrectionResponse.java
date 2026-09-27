package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 保养更正附录视图（只追加，不可变）。
 *
 * @param correctionId   附录标识
 * @param maintenanceId  关联的已完成保养记录标识
 * @param equipmentId    所属设备标识
 * @param cycleNo        关联周期号
 * @param content        更正内容
 * @param createdBy      附录创建人
 * @param requestId      创建附录请求的 requestId
 * @param createdAt      附录创建时刻（UTC）
 */
public record CorrectionResponse(
        long correctionId,
        long maintenanceId,
        String equipmentId,
        int cycleNo,
        String content,
        String createdBy,
        String requestId,
        Instant createdAt) {
}
