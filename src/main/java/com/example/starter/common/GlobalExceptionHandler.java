package com.example.starter.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：业务异常按状态码返回 message 与 details，参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        if (ex.details() != null) {
            body.put("details", ex.details());
        }
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "请求参数校验失败");
        body.put("details", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}