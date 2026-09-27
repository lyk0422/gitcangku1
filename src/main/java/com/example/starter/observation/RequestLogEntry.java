package com.example.starter.observation;

/**
 * 幂等去重记录：对应 request_log 表一行，仅成功请求会落库。
 *
 * @param requestId         全局唯一请求标识
 * @param fingerprint       完整指纹：许可域为含命中许可版本的 permitKey 指纹，其余操作为参数指纹
 * @param requestFingerprint 请求参数指纹（不含服务端命中结果）；许可域异参检测与重放短路使用，其他操作为 null
 * @param operation         操作类型：CREATE / MERGE / DELETE / RESOLVE 及许可域 PERMIT_* 操作
 * @param responseStatus    成功响应的 HTTP 状态码；提交过程中为 null
 * @param responseBody      成功响应体（JSON 原文），用于同键同参重放；提交过程中为 null
 */
public record RequestLogEntry(
        String requestId,
        String fingerprint,
        String requestFingerprint,
        String operation,
        Integer responseStatus,
        String responseBody) {
}
