package com.example.starter.idempotency;

/**
 * 写操作执行结果。
 *
 * @param status       响应 HTTP 状态码
 * @param responseJson 响应体 JSON；重放时返回首次成功时持久化的原始字节内容
 * @param replayed     是否为幂等重放结果
 */
public record WriteOutcome(
        int status,
        String responseJson,
        boolean replayed
) {
}
