package com.example.starter.artifact.repository;

/**
 * 幂等请求日志行记录。
 *
 * @param requestId      全局唯一请求 id
 * @param requestType    请求类型：register/retract/lock
 * @param payloadHash    请求参数的 SHA-256 摘要（十六进制）
 * @param responseStatus 原成功响应的 HTTP 状态码
 * @param responseBody   原成功响应的 JSON 报文
 */
public record RequestLogRow(String requestId, String requestType, String payloadHash,
                            int responseStatus, String responseBody) {
}
