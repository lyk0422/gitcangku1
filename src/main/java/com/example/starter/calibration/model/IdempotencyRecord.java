package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。仅成功提交后可见；同键同参重放首次完整响应，异参冲突。
 *
 * @param requestId    幂等请求 ID，全局唯一
 * @param operation    操作类型
 * @param identityJson 结构化身份 JSON（操作、操作者、资源、全部参数、期望版本）
 * @param responseJson 首次成功的完整响应 JSON；占位插入时为 null，提交前写回
 * @param createdAt    记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String identityJson,
        String responseJson,
        Instant createdAt) {
}
