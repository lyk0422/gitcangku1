package com.example.starter.evidence.dto;

import com.example.starter.evidence.IsolationStatus;

import java.time.LocalDateTime;

/**
 * 隔离记录视图。originalLocation 为隔离时容器位置快照，复核放行不改写。
 *
 * @param evidenceKey      被隔离证物业务键
 * @param containerKey     隔离时所在容器业务键
 * @param originalLocation 隔离时容器位置快照
 * @param status           隔离状态
 * @param reviewNote       复核结论说明；null 表示未复核
 * @param reviewedBy       复核操作人；null 表示未复核
 * @param reviewedAt       复核放行时刻（Asia/Shanghai）；null 表示未复核
 * @param createdAt        隔离创建时间（Asia/Shanghai）
 */
public record ContainerIsolationView(
        String evidenceKey,
        String containerKey,
        String originalLocation,
        IsolationStatus status,
        String reviewNote,
        String reviewedBy,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt) {
}
