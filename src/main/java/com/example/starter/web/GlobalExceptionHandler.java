package com.example.starter.web;

import com.example.starter.service.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：业务校验 422，状态冲突 409，资源缺失 404，请求格式错误 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常按携带的状态码返回。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatus());
        body.put("message", ex.getMessage());
        body.putAll(ex.getAttributes());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** 请求体解析或 Bean 校验失败返回 400。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "message", "请求格式或参数不合法"));
    }
}