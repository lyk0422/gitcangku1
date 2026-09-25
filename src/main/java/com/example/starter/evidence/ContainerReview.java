package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 双人复核封签记录实体，对应 container_review 表。记录只追加；
 * 同一容器同一 FAIL 检查人下，每名复核保管人至多一行。
 *
 * @param id           主键
 * @param containerKey 关联容器业务键
 * @param inspectorId  被复核 FAIL 巡检的检查人
 * @param reviewerId   复核保管人
 * @param note         复核说明，非空
 * @param reviewedAt   实际复核时刻（UTC）
 * @param createdAt    记录创建时间（Asia/Shanghai）
 */
public record ContainerReview(
        Long id,
        String containerKey,
        String inspectorId,
        String reviewerId,
        String note,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
