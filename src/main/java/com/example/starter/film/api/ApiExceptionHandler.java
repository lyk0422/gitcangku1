package com.example.starter.film.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理：业务异常映射其携带的状态码，参数校验映射为 422。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务规则拒绝：422 不可处理实体 / 409 冲突 / 404 不存在。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage()));
    }

    /** 唯一约束冲突（并发绕过幂等检查时的兜底）映射为 409。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("DUPLICATE_REQUEST", "请求冲突或重复提交"));
    }

    /** 请求体校验失败视为不可处理实体（422）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("VALIDATION_FAILED", message));
    }

    /** 约束校验失败视为不可处理实体（422）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body("VALIDATION_FAILED", ex.getMessage()));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
