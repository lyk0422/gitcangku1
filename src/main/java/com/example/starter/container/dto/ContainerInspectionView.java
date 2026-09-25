package com.example.starter.container.dto;

import java.time.LocalDateTime;

/**
 * 容器巡检记录视图。
 *
 * @param id               巡检记录主键
 * @param containerId      容器业务键
 * @param inspectKey       巡检幂等键
 * @param inspectorId      检查人
 * @param containerVersion 发起时容器版本
 * @param inspectedAt      实际巡检时刻（UTC）
 * @param nextInspectionAt 巡检后下次巡检时刻（UTC）
 * @param result           封签结果 PASS/FAIL
 * @param note             巡检说明
 * @param createdAt        记录创建时间（Asia/Shanghai）
 */
public record ContainerInspectionView(
        long id,
        String containerId,
        String inspectKey,
        String inspectorId,
        long containerVersion,
        LocalDateTime inspectedAt,
        LocalDateTime nextInspectionAt,
        String result,
        String note,
        LocalDateTime createdAt) {
}
