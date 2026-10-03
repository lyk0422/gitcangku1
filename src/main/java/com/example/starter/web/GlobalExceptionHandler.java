package com.example.starter.web;

import com.example.starter.service.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常转为统一错误响应，参数校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：409 冲突 / 422 规则不满足 / 404 不存在。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException error) {
        return ResponseEntity.status(error.status())
                .body(new ErrorResponse(error.code(), error.getMessage()));
    }

    /** 请求参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", "请求参数不完整或格式不正确"));
    }
}