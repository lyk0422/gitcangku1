package com.example.starter.race.repo;

/**
 * 请求日志行。
 *
 * @param requestId    全局唯一请求ID
 * @param action       操作类型
 * @param fingerprint  请求参数指纹
 * @param responseBody 成功响应JSON快照
 */
public record RequestLogRow(String requestId, String action, String fingerprint,
                            String responseBody) {
}
