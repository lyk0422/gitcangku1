package com.example.starter.restitution.web;

import com.example.starter.restitution.error.ApiException;
import com.example.starter.restitution.error.IdempotentReplayException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误语义：400 参数非法、403 自批、404 不存在、409 终态/版本/幂等冲突、422 裁决条件不满足。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 幂等重放：原样返回首次成功的状态码与响应体。
     */
    @ExceptionHandler(IdempotentReplayException.class)
    public ResponseEntity<String> replay(IdempotentReplayException ex) {
        return ResponseEntity.status(ex.getHttpStatus())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(ex.getResponseBody());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
        Map<String, Object> body = baseBody(ex.getCode(), ex.getMessage(), request);
        if (ex.getDetails() != null) {
            body.put("details", ex.getDetails());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = baseBody("bad_request", "请求参数校验失败", request);
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(baseBody("bad_request", "请求参数非法: " + ex.getMessage(), request));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException ex,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(baseBody("conflict", "唯一约束冲突", request));
    }

    private Map<String, Object> baseBody(String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }
}
