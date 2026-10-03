package com.example.starter.web;

import com.example.starter.service.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常按携带的状态码返回，参数校验失败返回 422。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiDtos.ErrorResponse(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiDtos.ErrorResponse("VALIDATION_FAILED", "请求参数不完整或格式错误"));
    }
}