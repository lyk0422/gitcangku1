package com.example.starter.container.dto;

import java.time.LocalDateTime;

/**
 * 待核验（FAIL 容器）证物视图。
 *
 * @param evidenceKey         证物业务键
 * @param custodianId         当前保管人
 * @param status              证物状态（PENDING_VERIFICATION）
 * @param containerId         所属容器业务键
 * @param failedInspectionId  触发待核验的 FAIL 巡检记录主键
 * @param failedAt            FAIL 实际巡检时刻（UTC）
 */
public record PendingVerificationView(
        String evidenceKey,
        String custodianId,
        String status,
        String containerId,
        long failedInspectionId,
        LocalDateTime failedAt) {
}
