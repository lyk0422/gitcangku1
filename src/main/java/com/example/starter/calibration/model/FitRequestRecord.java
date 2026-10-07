package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求登记。仅成功提交占键（失败随事务回滚）；
 * 同 requestId 重放时按 操作+资源+规范化全参数 结构化比较。
 *
 * @param requestId       全局请求 ID（幂等键）
 * @param operation       操作类型，如 FIT_SUBMIT / FIT_APPROVE
 * @param resource        资源标识（仪器 ID）
 * @param canonicalParams 规范化后的全参数（十进制按值、文本原样）
 * @param responseBody    首次成功的完整响应 JSON
 * @param createdAt       首次成功登记时间（UTC）
 */
public record FitRequestRecord(
        String requestId,
        String operation,
        String resource,
        String canonicalParams,
        String responseBody,
        Instant createdAt) {
}
