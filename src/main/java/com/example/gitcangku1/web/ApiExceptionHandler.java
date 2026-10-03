package com.example.gitcangku1.web;

import com.example.gitcangku1.service.ApiException;
import com.example.gitcangku1.service.ReleaseRejectedException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ReleaseRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(ReleaseRejectedException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
                "message", e.getMessage(),
                "rejectedRolls", e.rejectedRolls()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "请求参数校验失败"));
    }
}