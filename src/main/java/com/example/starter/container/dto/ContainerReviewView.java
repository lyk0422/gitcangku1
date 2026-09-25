package com.example.starter.container.dto;

import java.time.LocalDateTime;

/**
 * 容器复核封签记录视图。
 *
 * @param containerId 容器业务键
 * @param custodianId 复核保管人
 * @param note        复核说明
 * @param createdAt   复核时间（Asia/Shanghai）
 */
public record ContainerReviewView(
        String containerId,
        String custodianId,
        String note,
        LocalDateTime createdAt) {
}
