package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 写操作幂等去重记录。与业务写入同事务提交；业务失败随事务回滚，不占幂等键。
 *
 * @param id           幂等记录 ID（自增）
 * @param requestId    客户端请求 ID（幂等键），全局唯一
 * @param fingerprint  身份指纹：操作、操作者、资源、全部参数与期望版本的结构化 JSON
 * @param responseBody 首次成功的完整响应快照（JSON）
 * @param createdAt    记录时间（UTC）
 */
public record IdempotencyRecord(
        long id,
        String requestId,
        String fingerprint,
        String responseBody,
        Instant createdAt) {
}
