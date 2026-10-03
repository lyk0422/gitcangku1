package com.example.starter.web;

import com.example.starter.error.ApiException;
import com.example.starter.error.RejectionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一异常响应：{"error": 状态码, "message": 说明, "cellCodes": [...]?}。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.status());
        body.put("message", ex.getMessage());
        if (ex instanceof RejectionException rejection) {
            body.put("cellCodes", rejection.cellCodes());
        }
        return ResponseEntity.status(ex.status()).body(body);
    }

    /** 参数校验失败（400）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", 400);
        body.put("message", "请求参数不完整或不合法");
        return ResponseEntity.badRequest().body(body);
    }
}