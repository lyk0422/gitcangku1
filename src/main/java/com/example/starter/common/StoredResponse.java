package com.example.starter.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 幂等层输出：已序列化的响应体与状态码。重放时与原响应完全一致。
 */
public record StoredResponse(int status, String body) {

    public ResponseEntity<String> toEntity() {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}