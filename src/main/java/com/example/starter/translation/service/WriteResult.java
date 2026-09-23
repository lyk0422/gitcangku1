package com.example.starter.translation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 写操作结果：HTTP 状态码与 JSON 响应体。成功结果会序列化存入 request_log 用于重放。
 */
public record WriteResult(int status, String body) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
