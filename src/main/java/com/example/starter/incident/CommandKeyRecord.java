package com.example.starter.incident;

import java.time.Instant;

/**
 * 命令幂等键实体，对应 command_keys 表。
 * domain 为所属域；REAL/DRILL 两域同名命令键互不冲突、重放不跨域。
 * requestHash 为请求规范化参数的摘要；responseBody 为首次成功响应的 JSON，
 * 用于同键同参重放；responseStatus/responseBody 在事务提交前必定写入。
 */
public record CommandKeyRecord(
        long id,
        Domain domain,
        String commandKey,
        String operation,
        String requestHash,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {
}
