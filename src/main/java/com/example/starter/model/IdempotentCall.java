package com.example.starter.model;

import com.example.starter.domain.ActionType;

import java.time.Instant;

/**
 * 幂等调用记录 PO。只有业务成功提交的调用才会落库；
 * 业务失败随事务回滚，不占用 requestId。
 *
 * @param requestId 调用方幂等键
 * @param action 动作类型
 * @param fingerprint 首次调用参数指纹
 * @param targetRequestNo 关联申请编号
 * @param outcomeStatus 首次业务结果状态（申请/申请状态）
 * @param errorCode 预留错误码，成功为 null
 * @param message 首次结果说明
 * @param promotedRequestNo 撤销首次结果中接替成功的候补编号，无接替为 null
 * @param responseSnapshot 首次成功响应的 JSON 快照，重放原样返回
 * @param createdAt 记录时刻（UTC）
 */
public record IdempotentCall(
        String requestId,
        ActionType action,
        String fingerprint,
        String targetRequestNo,
        String outcomeStatus,
        String errorCode,
        String message,
        String promotedRequestNo,
        String responseSnapshot,
        Instant createdAt) {
}
