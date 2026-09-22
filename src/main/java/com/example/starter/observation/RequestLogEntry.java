package com.example.starter.observation;

/**
 * 幂等去重记录：对应 request_log 表的一行，仅成功请求会落库。
 *
 * @param requestId      全局唯一请求标识
 * @param fingerprint    请求操作与参数的指纹，同键异参时判定 409
 * @param operation      操作类型：CREATE / MERGE / DELETE
 * @param responseStatus 成功响应的 HTTP 状态码；提交过程中为 null
 * @param responseBody   成功响应体（JSON 原文），用于同键同参重放；提交过程中为 null
 */
public record RequestLogEntry(
        String requestId,
        String fingerprint,
        String operation,
        Integer responseStatus,
        String responseBody) {
}
