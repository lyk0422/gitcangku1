package com.example.starter.spectrum.web;

import com.example.starter.spectrum.exception.SpectrumException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：{ code, message, ... }，状态码与业务语义一一对应。
 */
@RestControllerAdvice
public class SpectrumExceptionHandler {

    /** 业务异常：400/404/409/422。 */
    @ExceptionHandler(SpectrumException.class)
    public ResponseEntity<Map<String, Object>> handleSpectrum(SpectrumException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        if (ex instanceof SpectrumException.BudgetExceeded budgetExceeded) {
            body.put("violations", budgetExceeded.getViolations());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** Bean Validation 失败：400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BAD_REQUEST");
        body.put("message", "request parameter validation failed");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 约束校验与缺少写操作 X-Request-Id 请求头：400。 */
    @ExceptionHandler({ConstraintViolationException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BAD_REQUEST");
        body.put("message", "malformed or incomplete request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
