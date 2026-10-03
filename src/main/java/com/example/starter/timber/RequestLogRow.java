package com.example.starter.timber;

/**
 * 幂等请求日志行：拆分、移交、签收共用的请求键记录。
 *
 * @param requestId 幂等请求键，全局唯一
 * @param operation 操作类型：SPLIT拆分，TRANSFER移交，SIGN签收
 * @param fingerprint 请求参数SHA-256指纹，同键异参判409
 * @param responseBody 原响应JSON，同参重放时原样返回，事务提交前为null
 */
public record RequestLogRow(String requestId, String operation, String fingerprint, String responseBody) {
}