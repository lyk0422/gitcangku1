package com.example.starter.curtailment.idempotency;

/**
 * 已记录的幂等命令。
 *
 * @param operation      操作类型
 * @param fingerprint    请求参数指纹
 * @param responseStatus 首次成功的 HTTP 状态；未完成时为 null
 * @param responseBody   首次成功的响应 JSON；未完成时为 null
 */
public record StoredCommand(String operation, String fingerprint, Integer responseStatus, String responseBody) {
}
