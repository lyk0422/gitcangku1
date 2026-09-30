package com.example.starter.web;

import com.example.starter.service.ConflictStateException;
import com.example.starter.service.IdempotencyConflictException;
import com.example.starter.service.NotFoundException;
import com.example.starter.service.UnprocessableRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口异常到 HTTP 状态码与错误体的统一映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Bean 校验失败，统一按 422 处理。
     *
     * @param ex      校验异常
     * @param request 当前请求
     * @return 422 错误体
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数校验失败"
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message, request);
    }

    /**
     * 业务参数不合法（潮高缺口、非正时间段、吃水非法、深度不足等）。
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 422 错误体，code 为可区分拒绝原因
     */
    @ExceptionHandler(UnprocessableRequestException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(UnprocessableRequestException ex,
                                                                   HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), request);
    }

    /**
     * 幂等参数冲突或业务状态冲突。
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 409 错误体
     */
    @ExceptionHandler({IdempotencyConflictException.class, ConflictStateException.class})
    public ResponseEntity<Map<String, Object>> handleConflict(RuntimeException ex,
                                                              HttpServletRequest request) {
        String code = ex instanceof IdempotencyConflictException
                ? "IDEMPOTENCY_PARAM_CONFLICT" : "STATE_CONFLICT";
        return build(HttpStatus.CONFLICT, code, ex.getMessage(), request);
    }

    /**
     * 资源不存在。
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 404 错误体
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    /**
     * 唯一约束冲突（如并发下重复 requestId 或版本号），按 409 返回。
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 409 错误体
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "STATE_CONFLICT", "资源已存在或并发冲突", request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code,
                                                      String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
