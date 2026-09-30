package com.example.starter.salinity.web;

import com.example.starter.salinity.dto.ErrorBody;
import com.example.starter.salinity.exception.BusinessException;
import com.example.starter.salinity.support.BusinessClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常映射为对应状态码，参数校验异常统一映射为 422。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final BusinessClock clock;

    /**
     * @param clock 业务时钟
     */
    public GlobalExceptionHandler(BusinessClock clock) {
        this.clock = clock;
    }

    /**
     * 业务异常（404/409/422）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorBody(ex.code(), ex.getMessage(), request.getRequestURI(), clock.now()));
    }

    /**
     * 请求体校验失败 → 422。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleBodyValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return unprocessable(message, request);
    }

    /**
     * 路径变量等约束校验失败 → 422。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> handleConstraint(ConstraintViolationException ex,
                                                      HttpServletRequest request) {
        return unprocessable(ex.getMessage(), request);
    }

    private ResponseEntity<ErrorBody> unprocessable(String message, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody("UNPROCESSABLE", message, request.getRequestURI(), clock.now()));
    }
}
