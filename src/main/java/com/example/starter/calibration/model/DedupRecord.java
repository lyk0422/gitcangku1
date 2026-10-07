package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 写请求幂等去重记录。requestId 全局唯一；身份哈希覆盖操作者、操作、资源、全参数及期望版本。
 * 与业务写入同一事务提交：业务失败则记录回滚，requestId 可复用。
 *
 * @param requestId    全局请求 ID（幂等键）
 * @param identityHash 请求身份 SHA-256
 * @param operation    操作类型
 * @param resource     资源标识
 * @param operator     操作者（非空）
 * @param responseBody 首次成功的完整响应体（JSON）；完成前为 null
 * @param createdAt    创建时间（UTC）
 */
public record DedupRecord(
        String requestId,
        String identityHash,
        String operation,
        String resource,
        String operator,
        String responseBody,
        Instant createdAt) {
}
