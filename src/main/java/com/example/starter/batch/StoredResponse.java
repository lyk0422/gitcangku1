package com.example.starter.batch;

/**
 * 幂等命令的已存储响应快照：HTTP 状态码 + 响应 JSON。
 */
public record StoredResponse(int status, String body) {
}
