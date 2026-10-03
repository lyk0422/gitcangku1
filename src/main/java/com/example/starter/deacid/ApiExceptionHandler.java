package com.example.starter.deacid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：业务异常按状态码返回，参数校验失败返回 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Contracts.ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new Contracts.ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Contracts.ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Contracts.ErrorBody("INVALID_ARGUMENT", "请求参数不完整或格式错误"));
    }
}