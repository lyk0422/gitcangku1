package com.example.starter.cue.web;

import com.example.starter.cue.service.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把业务异常与参数校验异常映射为带原因的错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", e.getMessage(), "status", e.status().value()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("请求参数无效");
        return ResponseEntity.badRequest()
                .body(Map.of("error", message, "status", 400));
    }
}