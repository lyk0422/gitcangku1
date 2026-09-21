package com.example.starter.incident;

import java.time.Instant;

/**
 * 命令幂等键实体，对应 command_keys 表。
 * requestHash 为请求规范化参数的摘要；responseBody 为首次成功响应的 JSON，
 * 用于同键同参重放；responseStatus/responseBody 在事务提交前必定写入。
 */
public record CommandKeyRecord(
        long id,
        String commandKey,
        String operation,
        String requestHash,
        Integer responseStatus,
        String responseBody,
        Instant createdAt) {
}
