package com.example.starter.translation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 写操作结果：HTTP 状态码与 JSON 响应体。成功结果会序列化存入 request_log 用于重放。
 */
public record WriteResult(int status, String body) {

    /**
     * 独立映射器：通过 ServiceLoader 注册 Jackson 扩展模块（如 JSR-310 时间模块），
     * 并与 Spring Boot 默认保持一致地以 ISO-8601 字符串输出时间，避免 Instant 无法序列化。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /** 以给定状态码序列化响应体。 */
    public static WriteResult of(int status, Object body) {
        try {
            return new WriteResult(status, MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /** 同 requestId 并发下已有成功记录，需回滚当前事务后重放，由 WriteExecutor 捕获处理。 */
    public static final class ReplaySignal extends RuntimeException {
        public ReplaySignal(String requestId, Throwable cause) {
            super("requestId 并发冲突，需重放: " + requestId, cause);
        }
    }
}
