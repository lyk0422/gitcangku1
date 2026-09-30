package com.example.starter.archival.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口异常统一处理。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务冲突：异参重放、跨批次占用、已封存修改、并发双入组等。 */
    @ExceptionHandler(ApiConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ApiConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** 资源不存在。 */
    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ApiNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法");
        return build(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
