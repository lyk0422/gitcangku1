package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器巡检记录实体，对应 container_inspection 表。记录只追加、不可变。
 *
 * @param id               主键
 * @param containerKey     关联容器业务键
 * @param inspectorId      检查人（容器负责人）
 * @param result           封签结果：PASS / FAIL
 * @param note             巡检说明；FAIL 时非空
 * @param inspectedAt      实际巡检时刻（UTC）
 * @param containerVersion 巡检发生时的容器版本号快照
 * @param createdAt        记录创建时间（Asia/Shanghai）
 */
public record ContainerInspection(
        Long id,
        String containerKey,
        String inspectorId,
        SealResult result,
        String note,
        LocalDateTime inspectedAt,
        long containerVersion,
        LocalDateTime createdAt) {
}
