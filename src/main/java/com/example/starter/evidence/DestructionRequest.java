package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 销毁申请实体，对应 destruction_request 表。只追加，状态不可回退。
 *
 * @param id            主键
 * @param requestKey    销毁申请业务键，全局唯一
 * @param status        申请状态
 * @param requestedBy   提交请求方
 * @param blockedReason 阻断不可变原因（JSON 文本快照）；{@code null} 表示未阻断
 * @param createdAt     提交时间（UTC）
 * @param blockedAt     阻断时刻（UTC）；{@code null} 表示未阻断
 * @param completedAt   完成销毁时刻（UTC）；{@code null} 表示未完成
 */
public record DestructionRequest(
        Long id,
        String requestKey,
        DestructionStatus status,
        String requestedBy,
        String blockedReason,
        LocalDateTime createdAt,
        LocalDateTime blockedAt,
        LocalDateTime completedAt) {
}
