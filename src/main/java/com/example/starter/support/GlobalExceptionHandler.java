package com.example.starter.support;

import com.example.starter.api.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常与请求校验异常到 HTTP 响应的映射。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public org.springframework.http.ResponseEntity<ApiError> handleApi(ApiException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatus())
                .body(new ApiError(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class})
    public org.springframework.http.ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        String message = "请求参数不合法";
        if (ex instanceof MissingRequestHeaderException header) {
            message = "缺少请求头 " + header.getHeaderName();
        } else if (ex instanceof MethodArgumentNotValidException validation
                && validation.getBindingResult().getFieldError() != null) {
            message = validation.getBindingResult().getFieldError().getDefaultMessage();
        } else if (ex instanceof ConstraintViolationException violation
                && !violation.getConstraintViolations().isEmpty()) {
            message = violation.getConstraintViolations().iterator().next().getMessage();
        }
        return org.springframework.http.ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", message));
    }
}
