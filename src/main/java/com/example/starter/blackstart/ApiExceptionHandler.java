package com.example.starter.blackstart;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 响应的映射：404 计划不存在，409 幂等/终态冲突，业务消息原样返回。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 错误响应体。 */
    public record ErrorBody(String error, Instant timestamp) {
    }

    @ExceptionHandler(BlackStartException.class)
    public ResponseEntity<ErrorBody> handle(BlackStartException ex) {
        return ResponseEntity.status(ex.httpStatus())
                .body(new ErrorBody(ex.getMessage(), Instant.now()));
    }
}