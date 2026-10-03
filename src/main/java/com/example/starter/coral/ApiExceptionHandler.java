package com.example.starter.coral;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到HTTP状态的映射：409=资源冲突，422=状态语义不允许，404=不存在，400=参数校验失败。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 统一错误响应体。 */
    public record ErrorBody(String error, Instant timestamp) {
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorBody> handleBiz(BizException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorBody(e.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody("请求参数校验失败", Instant.now()));
    }
}