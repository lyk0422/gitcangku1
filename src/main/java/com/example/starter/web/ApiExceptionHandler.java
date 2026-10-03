package com.example.starter.web;

import com.example.starter.service.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：业务拒绝返回原因码与对应 HTTP 状态。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务异常：按原因码返回（拒绝为 409，不存在为 404）。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Dtos.ErrorResponse> business(BusinessException e) {
        return ResponseEntity.status(e.status())
                .body(new Dtos.ErrorResponse(e.reason().name(), e.getMessage()));
    }

    /** 参数校验失败：400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Dtos.ErrorResponse> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new Dtos.ErrorResponse("VALIDATION_FAILED", "请求参数不合法"));
    }
}