package com.example.starter.incident;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应：业务异常按 ApiException 携带的状态码返回，
 * 请求体/请求头解析失败归为 400，其余未预期异常归为 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误响应体：code 为机器可区分错误码，message 为人读描述，
     * details 为可选结构化冲突明细（无明细时序列化为 null）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Object details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorBody(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ErrorBody> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("BAD_REQUEST", "请求格式非法: " + ex.getMessage(), null));
    }
}
