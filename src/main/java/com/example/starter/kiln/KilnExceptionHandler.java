package com.example.starter.kiln;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常响应：业务异常返回状态码、错误码与结构化细节。
 */
@RestControllerAdvice
public class KilnExceptionHandler {

    @ExceptionHandler(KilnException.class)
    public ResponseEntity<Map<String, Object>> handleKiln(KilnException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", exception.code());
        body.put("message", exception.getMessage());
        if (exception.details() != null) {
            body.put("details", exception.details());
        }
        return ResponseEntity.status(exception.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_INVALID");
        body.put("message", "请求参数校验失败");
        body.put("details", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).toList());
        return ResponseEntity.badRequest().body(body);
    }
}