package com.example.starter.service;

import java.util.function.Supplier;

/**
 * 写操作幂等执行器：同键同参重放原成功结果，异参409，失败不占键；
 * 业务变更与幂等记录在同一事务内原子提交。
 */
public interface IdempotencyService {

    /**
     * 写操作执行结果。
     *
     * @param statusCode 原始或重放的 HTTP 状态码
     * @param body       原始或重放的响应体
     * @param replayed   是否为幂等重放
     */
    record WriteOutcome<T>(int statusCode, T body, boolean replayed) {

        public static <T> WriteOutcome<T> created(T body) {
            return new WriteOutcome<>(201, body, false);
        }

        public static <T> WriteOutcome<T> ok(T body) {
            return new WriteOutcome<>(200, body, false);
        }
    }

    /**
     * 在幂等保护下执行写操作。
     *
     * @param requestId   全局唯一请求ID
     * @param operation   操作类型
     * @param fingerprint 请求参数指纹（异参检测）
     * @param bodyType    成功响应体类型，用于重放时反序列化
     * @param action      首次执行的业务动作，返回状态码与响应体
     */
    <T> WriteOutcome<T> execute(String requestId, String operation, String fingerprint,
                                Class<T> bodyType, Supplier<WriteOutcome<T>> action);
}
