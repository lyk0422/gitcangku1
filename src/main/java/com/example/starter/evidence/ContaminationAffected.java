package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 污染传播路径实体，对应 contamination_affected 表。只追加，冻结传播来源。
 *
 * @param id               主键
 * @param contaminationKey 所属污染登记业务键
 * @param evidenceKey      受影响证物业务键
 * @param sourceBatchKey   传播来源接触批次业务键；null 表示污染起点证物
 * @param createdAt        标记时刻（UTC）
 */
public record ContaminationAffected(
        Long id,
        String contaminationKey,
        String evidenceKey,
        String sourceBatchKey,
        LocalDateTime createdAt) {
}
