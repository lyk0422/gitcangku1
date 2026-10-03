package com.example.starter.common;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常返回状态码与可区分的原因码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of(
                "reason", exception.reason().name(),
                "message", exception.getMessage()));
    }

    /**
     * 参数校验异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "reason", "VALIDATION_FAILED",
                "message", "请求参数校验失败"));
    }
}