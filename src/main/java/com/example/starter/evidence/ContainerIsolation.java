package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 容器隔离记录实体，对应 container_isolation 表。
 * 异常巡检确认时对当时箱内全部证物各写一条；复核放行仅更新状态与复核字段，
 * originalLocation 快照永不改写。
 *
 * @param id               主键
 * @param inspectionId     关联容器巡检记录主键
 * @param evidenceKey      被隔离证物业务键
 * @param containerKey     隔离时所在容器业务键
 * @param originalLocation 隔离时容器位置快照
 * @param status           隔离状态
 * @param reviewNote       复核结论说明；null 表示未复核
 * @param reviewedBy       复核操作人；null 表示未复核
 * @param reviewedAt       复核放行时刻（Asia/Shanghai）；null 表示未复核
 * @param createdAt        隔离创建时间（Asia/Shanghai）
 */
public record ContainerIsolation(
        Long id,
        Long inspectionId,
        String evidenceKey,
        String containerKey,
        String originalLocation,
        IsolationStatus status,
        String reviewNote,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
