package com.example.starter.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把业务异常与参数校验异常转换为稳定的错误响应体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误响应体。
     *
     * @param code 稳定错误码
     * @param message 错误说明
     */
    public record ErrorBody(String code, String message) {
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorBody> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("VALIDATION_FAILED", message));
    }
}