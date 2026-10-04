package com.example.starter.observation;

/**
 * 会话批次/关闭请求的幂等回执：对应 session_receipt 表的一行，仅成功请求落库。
 *
 * @param requestId      批次/关闭请求标识（幂等去重键）
 * @param fingerprint    操作、操作者、资源及全部参数的结构化指纹，同键异参判定 409
 * @param operation      操作类型：BATCH_UPLOAD / SESSION_CLOSE
 * @param responseStatus 成功响应的 HTTP 状态码；提交过程中为 null
 * @param responseBody   成功响应体（JSON 原文），用于同键同参重放；提交过程中为 null
 */
public record SessionReceipt(
        String requestId,
        String fingerprint,
        String operation,
        Integer responseStatus,
        String responseBody) {
}