package com.example.starter.web;

import com.example.starter.error.ApiException;
import com.example.starter.web.dto.ErrorResponse;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将业务异常统一翻译为错误响应；不向客户端泄露堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常按其携带状态码返回。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage());
    }

    /** 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("validation failed");
        return build(400, message);
    }

    /** 请求体缺失或不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(400, "malformed JSON request body");
    }

    /** 唯一约束冲突（实验编号重复等）。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateKeyException ex) {
        return build(409, "resource already exists");
    }

    private static ResponseEntity<ErrorResponse> build(int status, String message) {
        ErrorResponse body = new ErrorResponse(
                status,
                HttpStatus.valueOf(status).getReasonPhrase(),
                message,
                Instant.now());
        return ResponseEntity.status(status).body(body);
    }
}
